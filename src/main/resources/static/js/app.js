const API = '/api/simulation';

let running = false;
let timer = null;
let latestSnapshot = null;
let selectedHouseId = null;
let stepMinutes = 10; // updated from every snapshot; drives chart window + fast-forward size

const el = (id) => document.getElementById(id);

const playBtn = el('playBtn');
const stepBtn = el('stepBtn');
const ffBtn = el('ffBtn');
const resetBtn = el('resetBtn');
const speedSlider = el('speedSlider');
const detailPanel = el('houseDetail');
const detailContent = el('houseDetailContent');
const configPanel = el('configPanel');
const configBtn = el('configBtn');

// ---------------------------------------------------------------- Chart

const ctx = document.getElementById('powerChart').getContext('2d');
const chart = new Chart(ctx, {
  type: 'line',
  data: {
    labels: [],
    datasets: [
      {
        label: 'Demand (kW)',
        data: [],
        borderColor: '#f0a83a',
        backgroundColor: 'rgba(240,168,58,0.08)',
        fill: true,
        tension: 0.25,
        pointRadius: 0,
        borderWidth: 2,
      },
      {
        label: 'Generation (kW)',
        data: [],
        borderColor: '#35c9b0',
        backgroundColor: 'rgba(53,201,176,0.08)',
        fill: true,
        tension: 0.25,
        pointRadius: 0,
        borderWidth: 2,
      },
      {
        label: 'Net import (kW)',
        data: [],
        borderColor: '#4c8dff',
        borderDash: [4, 3],
        fill: false,
        tension: 0.25,
        pointRadius: 0,
        borderWidth: 1.5,
      },
    ],
  },
  options: {
    animation: false,
    responsive: true,
    interaction: { mode: 'index', intersect: false },
    scales: {
      x: {
        ticks: { color: '#7e8c90', maxTicksLimit: 10, font: { family: 'JetBrains Mono', size: 10 } },
        grid: { color: 'rgba(35,46,51,0.6)' },
      },
      y: {
        ticks: { color: '#7e8c90', font: { family: 'JetBrains Mono', size: 10 } },
        grid: { color: 'rgba(35,46,51,0.6)' },
        title: { display: true, text: 'kW', color: '#7e8c90' },
      },
    },
    plugins: {
      legend: {
        labels: { color: '#edf2f3', font: { family: 'Space Grotesk', size: 11 }, boxWidth: 14 },
      },
      tooltip: {
        titleFont: { family: 'JetBrains Mono' },
        bodyFont: { family: 'JetBrains Mono' },
      },
    },
  },
});

function updateChart(history) {
  const ticksPerDay = Math.round(1440 / stepMinutes);
  const windowed = history.slice(-ticksPerDay); // last 24h for readability
  chart.data.labels = windowed.map(p => (p.day > 1 ? `D${p.day} ` : '') + p.timeLabel);
  chart.data.datasets[0].data = windowed.map(p => p.totalDemandKw);
  chart.data.datasets[1].data = windowed.map(p => p.totalGenerationKw);
  chart.data.datasets[2].data = windowed.map(p => p.netImportKw);
  chart.update('none');
}

// ------------------------------------------------------------ House grid

function assetIcons(h) {
  let s = '';
  if (h.hasHeatPump) s += '🔥';
  if (h.hasPv) s += '☀️';
  if (h.hasEvCharger) s += '🔌';
  return s || '—';
}

function ensureHouseGrid(houses) {
  const grid = el('houseGrid');
  if (grid.children.length === houses.length) return;
  grid.innerHTML = '';
  houses.forEach((h) => {
    const tile = document.createElement('div');
    tile.className = 'house-tile';
    tile.id = `tile-${h.id}`;
    tile.innerHTML = `
      <span class="tile-id">${h.id}</span>
      <span class="tile-icons">${assetIcons(h)}</span>
      <span class="tile-net">–</span>
    `;
    tile.addEventListener('click', () => showDetail(h.id));
    grid.appendChild(tile);
  });
}

function updateHouseGrid(houses) {
  ensureHouseGrid(houses);
  houses.forEach((h) => {
    const tile = el(`tile-${h.id}`);
    if (!tile) return;
    const net = h.netKw;
    tile.classList.remove('importing', 'exporting');
    if (Math.abs(net) < 0.05) {
      tile.style.removeProperty('--intensity');
    } else if (net > 0) {
      tile.classList.add('importing');
      tile.style.setProperty('--intensity', Math.min(0.85, 0.15 + Math.abs(net) / 8).toFixed(2));
    } else {
      tile.classList.add('exporting');
      tile.style.setProperty('--intensity', Math.min(0.85, 0.15 + Math.abs(net) / 6).toFixed(2));
    }
    tile.querySelector('.tile-net').textContent = `${net > 0 ? '+' : ''}${net.toFixed(1)}`;
  });
}

// ----------------------------------------------------------------- Table

function updateTable(houses) {
  const body = el('houseTableBody');
  body.innerHTML = houses.map((h) => {
    const netClass = h.netKw > 0.05 ? 'net-import' : (h.netKw < -0.05 ? 'net-export' : 'net-idle');
    const chargingFlag = h.evCharging ? '<span class="charging-flag">CHARGING</span>' : '';
    return `
      <tr data-id="${h.id}">
        <td>${h.name}</td>
        <td class="asset-icons">${assetIcons(h)}</td>
        <td>${h.baseLoadKw.toFixed(2)} kW</td>
        <td>${h.hasHeatPump ? h.heatPumpLoadKw.toFixed(2) + ' kW' : '—'}</td>
        <td>${h.hasEvCharger ? h.evLoadKw.toFixed(2) + ' kW' + chargingFlag : '—'}</td>
        <td>${h.hasPv ? h.pvGenerationKw.toFixed(2) + ' kW' : '—'}</td>
        <td class="${netClass}">${h.netKw > 0 ? '+' : ''}${h.netKw.toFixed(2)} kW</td>
        <td class="meter-cell">${h.cumulativeConsumptionKwh.toFixed(1)} kWh</td>
        <td class="meter-cell">${h.hasPv ? h.cumulativeGenerationKwh.toFixed(1) + ' kWh' : '—'}</td>
      </tr>`;
  }).join('');

  body.querySelectorAll('tr').forEach((row) => {
    row.addEventListener('click', () => showDetail(parseInt(row.dataset.id, 10)));
  });
}

// ------------------------------------------------------- Public chargers

function ensurePublicChargerGrid(chargers) {
  const grid = el('publicChargerGrid');
  if (grid.children.length === chargers.length) return;
  grid.innerHTML = '';
  chargers.forEach((c) => {
    const tile = document.createElement('div');
    tile.className = 'charger-tile';
    tile.id = `charger-${c.id}`;
    tile.innerHTML = `
      <span class="charger-name">${c.name}</span>
      <div class="charger-meta">
        <span class="charger-power">${c.powerKw.toFixed(0)} kW rated</span>
        <span class="charger-status">–</span>
      </div>
      <div class="charger-load">0.0 kW</div>
      <div class="charger-cumulative">0.0 kWh total</div>
    `;
    grid.appendChild(tile);
  });
}

function updatePublicChargers(chargers) {
  ensurePublicChargerGrid(chargers);
  let totalLoad = 0;
  chargers.forEach((c) => {
    totalLoad += c.currentLoadKw;
    const tile = el(`charger-${c.id}`);
    if (!tile) return;
    tile.classList.toggle('occupied', c.occupied);
    tile.querySelector('.charger-status').textContent = c.occupied ? 'IN USE' : 'FREE';
    tile.querySelector('.charger-load').textContent = `${c.currentLoadKw.toFixed(1)} kW`;
    tile.querySelector('.charger-cumulative').textContent = `${c.cumulativeEnergyKwh.toFixed(1)} kWh total`;
  });
  el('publicChargerLoadLabel').textContent = `${totalLoad.toFixed(1)} kW total`;
}

// -------------------------------------------------------- Cumulative energy

const CUMULATIVE_ROWS = [
  { key: 'cumulativeBaseLoadKwh', label: '🏠 Base load', color: '#7e8c90' },
  { key: 'cumulativeHeatPumpKwh', label: '🔥 Heat pumps', color: '#e5533d' },
  { key: 'cumulativeEvHomeKwh', label: '🔌 EV (home)', color: '#4c8dff' },
  { key: 'cumulativeEvPublicKwh', label: '🅿️ EV (public)', color: '#a06cff' },
  { key: 'cumulativePvKwh', label: '☀️ PV generation', color: '#35c9b0' },
];

function updateCumulative(s) {
  const container = el('cumulativeBars');
  const max = Math.max(1, ...CUMULATIVE_ROWS.map(r => s[r.key]));
  if (container.children.length !== CUMULATIVE_ROWS.length) {
    container.innerHTML = CUMULATIVE_ROWS.map(r => `
      <div class="cumulative-row" data-key="${r.key}">
        <span class="cumulative-label">${r.label}</span>
        <div class="cumulative-track"><div class="cumulative-fill" style="background:${r.color}"></div></div>
        <span class="cumulative-value">0 kWh</span>
      </div>
    `).join('');
  }
  CUMULATIVE_ROWS.forEach((r) => {
    const row = container.querySelector(`[data-key="${r.key}"]`);
    if (!row) return;
    const value = s[r.key];
    row.querySelector('.cumulative-fill').style.width = `${Math.max(2, (value / max) * 100)}%`;
    row.querySelector('.cumulative-value').textContent = `${value.toFixed(1)} kWh`;
  });
}

// ---------------------------------------------------------------- Detail

function showDetail(id) {
  selectedHouseId = id;
  renderDetail();
}

function renderDetail() {
  if (!selectedHouseId || !latestSnapshot) return;
  const h = latestSnapshot.houses.find(x => x.id === selectedHouseId);
  if (!h) return;
  detailPanel.classList.remove('hidden');
  detailContent.innerHTML = `
    <h3>${h.name}</h3>
    <div class="detail-row"><span>Assets</span><b>${assetIcons(h)}</b></div>
    <div class="detail-row"><span>Base load</span><b>${h.baseLoadKw.toFixed(2)} kW</b></div>
    <div class="detail-row"><span>Heat pump</span><b>${h.hasHeatPump ? h.heatPumpLoadKw.toFixed(2) + ' kW' : 'n/a'}</b></div>
    <div class="detail-row"><span>EV charger</span><b>${h.hasEvCharger ? (h.evCharging ? h.evLoadKw.toFixed(2) + ' kW (charging)' : 'idle') : 'n/a'}</b></div>
    <div class="detail-row"><span>PV capacity</span><b>${h.hasPv ? h.pvCapacityKw.toFixed(1) + ' kWp' : 'n/a'}</b></div>
    <div class="detail-row"><span>PV generation</span><b>${h.hasPv ? h.pvGenerationKw.toFixed(2) + ' kW' : 'n/a'}</b></div>
    <div class="detail-row"><span>Total load</span><b>${h.totalLoadKw.toFixed(2)} kW</b></div>
    <div class="detail-row"><span>Net grid flow</span><b>${h.netKw > 0 ? '+' : ''}${h.netKw.toFixed(2)} kW</b></div>
    <div class="detail-row"><span>Meter: consumed</span><b>${h.cumulativeConsumptionKwh.toFixed(1)} kWh</b></div>
    <div class="detail-row"><span>Meter: generated</span><b>${h.cumulativeGenerationKwh.toFixed(1)} kWh</b></div>
  `;
}

el('detailClose').addEventListener('click', () => {
  detailPanel.classList.add('hidden');
  selectedHouseId = null;
});

// --------------------------------------------------------- Config panel

async function loadConfigIntoForm() {
  const cfg = await apiGet('/config');
  el('cfgSeed').value = cfg.seed ?? '';
  el('cfgStepMinutes').value = String(cfg.stepMinutes ?? 10);
  el('cfgHouseCount').value = cfg.houseCount;
  el('cfgHeatPumpPct').value = Math.round(cfg.heatPumpProbability * 100);
  el('cfgPvPct').value = Math.round(cfg.pvProbability * 100);
  el('cfgEvPct').value = Math.round(cfg.evChargerProbability * 100);
  el('footerSeed').textContent = cfg.seed ?? '–';
  stepMinutes = cfg.stepMinutes ?? 10;
  updateStepLabels();
}

async function applyConfigFromForm() {
  const seedRaw = el('cfgSeed').value.trim();
  const updates = {
    houseCount: parseInt(el('cfgHouseCount').value, 10) || 30,
    stepMinutes: parseInt(el('cfgStepMinutes').value, 10) || 10,
    heatPumpProbability: clampPct(el('cfgHeatPumpPct').value) / 100,
    pvProbability: clampPct(el('cfgPvPct').value) / 100,
    evChargerProbability: clampPct(el('cfgEvPct').value) / 100,
    seed: seedRaw === '' ? null : parseInt(seedRaw, 10),
  };
  pause();
  const res = await fetch(`${API}/config`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(updates),
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({ error: 'Could not apply configuration.' }));
    alert(err.error || 'Could not apply configuration.');
    return;
  }
  const snapshot = await res.json();
  selectedHouseId = null;
  detailPanel.classList.add('hidden');
  document.getElementById('houseGrid').innerHTML = '';
  document.getElementById('publicChargerGrid').innerHTML = '';
  render(snapshot);
  await loadConfigIntoForm();
  configPanel.classList.add('hidden');
}

function clampPct(v) {
  const n = parseFloat(v);
  if (isNaN(n)) return 0;
  return Math.max(0, Math.min(100, n));
}

configBtn.addEventListener('click', async () => {
  await loadConfigIntoForm();
  configPanel.classList.remove('hidden');
});

el('configClose').addEventListener('click', () => configPanel.classList.add('hidden'));
el('cfgApplyBtn').addEventListener('click', applyConfigFromForm);
el('cfgLoadBtn').addEventListener('click', loadConfigIntoForm);

// ------------------------------------------------------------- Summary

function updateSummary(s) {
  el('dateValue').textContent = s.simulatedDate;
  el('timeValue').textContent = s.timeLabel;
  el('seasonValue').textContent = `${s.season} · ${s.monthName}`;
  el('tempValue').textContent = `${s.outdoorTempC.toFixed(1)}°C`;
  el('cloudValue').textContent = `${Math.round(s.cloudFactor * 100)}%`;
  el('sunValue').textContent = `${s.sunriseLabel} – ${s.sunsetLabel}`;

  el('demandValue').textContent = s.totalDemandKw.toFixed(1);
  el('generationValue').textContent = s.totalGenerationKw.toFixed(1);

  const netCard = el('netCard');
  netCard.classList.remove('importing', 'exporting');
  if (s.netImportKw >= 0) {
    netCard.classList.add('importing');
    el('netValue').textContent = s.netImportKw.toFixed(1);
    el('netUnit').textContent = 'kW import';
  } else {
    netCard.classList.add('exporting');
    el('netValue').textContent = Math.abs(s.netImportKw).toFixed(1);
    el('netUnit').textContent = 'kW export';
  }

  el('countHeatPump').textContent = s.assetCountHeatPump;
  el('countPv').textContent = s.assetCountPv;
  el('countEv').textContent = s.assetCountEvCharger;
  el('countPublicEv').textContent = s.publicChargerCount;
}

// --------------------------------------------------------------- Render

function render(snapshot) {
  latestSnapshot = snapshot;
  if (snapshot.stepMinutes) {
    stepMinutes = snapshot.stepMinutes;
  }
  updateStepLabels();
  updateSummary(snapshot);
  updateHouseGrid(snapshot.houses);
  updateTable(snapshot.houses);
  updateChart(snapshot.history);
  updatePublicChargers(snapshot.publicChargers);
  updateCumulative(snapshot);
  renderDetail();
}

function updateStepLabels() {
  stepBtn.textContent = `Step +${stepMinutes}m`;
  const ffMinutes = 240; // fast-forward span: 4 hours
  ffBtn.textContent = `⏩ +${Math.round(ffMinutes / 60)}h`;
  ffBtn.dataset.ticks = Math.max(1, Math.round(ffMinutes / stepMinutes));
}

// ---------------------------------------------------------------- Fetch

async function apiPost(path) {
  const res = await fetch(`${API}${path}`, { method: 'POST' });
  return res.json();
}

async function apiGet(path) {
  const res = await fetch(`${API}${path}`);
  return res.json();
}

async function doStep() {
  const snapshot = await apiPost('/step');
  render(snapshot);
}

async function doReset() {
  pause();
  const snapshot = await apiPost('/reset');
  selectedHouseId = null;
  detailPanel.classList.add('hidden');
  document.getElementById('houseGrid').innerHTML = '';
  document.getElementById('publicChargerGrid').innerHTML = '';
  render(snapshot);
  el('footerSeed').textContent = snapshot.houses.length ? await currentSeedLabel() : '–';
}

async function currentSeedLabel() {
  try {
    const cfg = await apiGet('/config');
    return cfg.seed ?? '–';
  } catch {
    return '–';
  }
}

async function doFastForward() {
  const ticks = parseInt(ffBtn.dataset.ticks, 10) || Math.max(1, Math.round(240 / stepMinutes));
  const snapshot = await apiPost(`/step/${ticks}`);
  render(snapshot);
}

// ---------------------------------------------------------- Play / pause

function play() {
  running = true;
  playBtn.textContent = '⏸ Pause';
  playBtn.classList.add('running');
  const speed = parseInt(speedSlider.value, 10);
  timer = setInterval(doStep, speed);
}

function pause() {
  running = false;
  playBtn.textContent = '▶ Run';
  playBtn.classList.remove('running');
  clearInterval(timer);
  timer = null;
}

playBtn.addEventListener('click', () => (running ? pause() : play()));
stepBtn.addEventListener('click', () => { if (!running) doStep(); });
ffBtn.addEventListener('click', () => { if (!running) doFastForward(); });
resetBtn.addEventListener('click', doReset);

speedSlider.addEventListener('input', () => {
  if (running) {
    clearInterval(timer);
    timer = setInterval(doStep, parseInt(speedSlider.value, 10));
  }
});

// ------------------------------------------------------------------ Init

(async function init() {
  const snapshot = await apiGet('/state');
  render(snapshot);
  el('footerSeed').textContent = await currentSeedLabel();
})();
