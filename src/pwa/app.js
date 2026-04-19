const serverIdEl = document.getElementById("serverId");
const leaderIdEl = document.getElementById("leaderId");
const isLeaderEl = document.getElementById("isLeader");
const totalNodesEl = document.getElementById("totalNodes");

const aliveCountEl = document.getElementById("aliveCount");
const suspectCountEl = document.getElementById("suspectCount");
const deadCountEl = document.getElementById("deadCount");

const resourcesListEl = document.getElementById("resourcesList");
const nodesTableBodyEl = document.getElementById("nodesTableBody");
const lastUpdateEl = document.getElementById("lastUpdate");
const refreshBtn = document.getElementById("refreshBtn");
const installBtn = document.getElementById("installBtn");
const statusBadge = document.getElementById("statusBadge");

let deferredPrompt = null;

async function getJson(url) {
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error("HTTP " + response.status);
  }
  return response.json();
}

function setConnectionStatus() {
  if (navigator.onLine) {
    statusBadge.textContent = "Online";
    statusBadge.className = "badge online";
  } else {
    statusBadge.textContent = "Offline";
    statusBadge.className = "badge offline";
  }
}

function renderSummary(data) {
  serverIdEl.textContent = data.serverId ?? "--";
  leaderIdEl.textContent = data.leaderId ?? "--";
  isLeaderEl.textContent = data.isLeader ? "Sí" : "No";
  totalNodesEl.textContent = data.totalNodes ?? 0;

  aliveCountEl.textContent = data.alive ?? 0;
  suspectCountEl.textContent = data.suspect ?? 0;
  deadCountEl.textContent = data.dead ?? 0;

  resourcesListEl.innerHTML = "";
  if (data.resources) {
    Object.entries(data.resources).forEach(([type, count]) => {
      const div = document.createElement("div");
      div.className = "resource-item";
      div.textContent = `${type}: ${count}`;
      resourcesListEl.appendChild(div);
    });
  }
}

function renderNodes(nodes) {
  if (!nodes || nodes.length === 0) {
    nodesTableBodyEl.innerHTML = `<tr><td colspan="4">Sin nodos registrados</td></tr>`;
    return;
  }

  nodesTableBodyEl.innerHTML = nodes.map(node => `
    <tr>
      <td>${node.nodeId}</td>
      <td>${node.type}</td>
      <td><span class="status-pill status-${node.status}">${node.status}</span></td>
      <td>${node.lastValue}</td>
    </tr>
  `).join("");
}

async function loadDashboard() {
  try {
    const [summary, nodes] = await Promise.all([
      getJson("/api/dashboard/summary"),
      getJson("/api/nodes")
    ]);

    localStorage.setItem("dashboard-summary", JSON.stringify(summary));
    localStorage.setItem("nodes-data", JSON.stringify(nodes));

    renderSummary(summary);
    renderNodes(nodes);
    lastUpdateEl.textContent = "Última actualización: " + new Date().toLocaleString();
  } catch (error) {
    const summaryCache = localStorage.getItem("dashboard-summary");
    const nodesCache = localStorage.getItem("nodes-data");

    if (summaryCache) renderSummary(JSON.parse(summaryCache));
    if (nodesCache) renderNodes(JSON.parse(nodesCache));

    lastUpdateEl.textContent = "Última actualización: datos cargados desde caché";
  }
}

refreshBtn.addEventListener("click", loadDashboard);

window.addEventListener("online", setConnectionStatus);
window.addEventListener("offline", setConnectionStatus);

window.addEventListener("beforeinstallprompt", (event) => {
  event.preventDefault();
  deferredPrompt = event;
  installBtn.hidden = false;
});

installBtn.addEventListener("click", async () => {
  if (!deferredPrompt) return;
  deferredPrompt.prompt();
  await deferredPrompt.userChoice;
  deferredPrompt = null;
  installBtn.hidden = true;
});

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("/service-worker.js")
    .then(() => console.log("Service Worker registrado"))
    .catch(err => console.error("SW error", err));
}

setConnectionStatus();
loadDashboard();
setInterval(loadDashboard, 5000);