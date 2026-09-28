// Visor Urbano SPGG, app de escritorio (Windows y macOS)
const { app, BrowserWindow, Menu, shell, session } = require('electron');
const path = require('path');

const START = path.join(__dirname, 'app', 'inicio.html');
const VISOR_HOST = 'visorurbano.sanpedro.gob.mx';
let win;

function isInternal(url) {
  try {
    const u = new URL(url);
    return u.protocol === 'file:' || u.hostname === VISOR_HOST || u.hostname.endsWith('.sanpedro.gob.mx');
  } catch { return false; }
}

function createWindow() {
  win = new BrowserWindow({
    width: 1280,
    height: 820,
    minWidth: 380,
    minHeight: 500,
    title: 'Visor Urbano',
    backgroundColor: '#193275',
    icon: path.join(__dirname, 'app', 'logo.png'),
    autoHideMenuBar: true,
    webPreferences: { contextIsolation: true, sandbox: true }
  });

  win.loadFile(START);

  // Enlaces que abren ventana nueva: los del visor se quedan en la app, el resto va al navegador
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (isInternal(url)) {
      return { action: 'allow', overrideBrowserWindowOptions: { backgroundColor: '#ffffff', autoHideMenuBar: true } };
    }
    shell.openExternal(url);
    return { action: 'deny' };
  });

  // Mantener el título fijo
  win.on('page-title-updated', e => e.preventDefault());
}

function buildMenu() {
  const nav = {
    label: 'Navegar',
    submenu: [
      { label: 'Inicio', accelerator: 'CmdOrCtrl+H', click: () => win && win.loadFile(START) },
      { label: 'Atrás', accelerator: process.platform === 'darwin' ? 'Cmd+[' : 'Alt+Left',
        click: () => win && win.webContents.navigationHistory.canGoBack() && win.webContents.navigationHistory.goBack() },
      { label: 'Adelante', accelerator: process.platform === 'darwin' ? 'Cmd+]' : 'Alt+Right',
        click: () => win && win.webContents.navigationHistory.canGoForward() && win.webContents.navigationHistory.goForward() },
      { type: 'separator' },
      { role: 'reload', label: 'Recargar' },
      { role: 'togglefullscreen', label: 'Pantalla completa' },
      { type: 'separator' },
      { role: 'zoomIn', label: 'Acercar' },
      { role: 'zoomOut', label: 'Alejar' },
      { role: 'resetZoom', label: 'Tamaño normal' }
    ]
  };
  const template = [];
  if (process.platform === 'darwin') template.push({ role: 'appMenu', label: 'Visor Urbano' });
  template.push({ role: 'editMenu', label: 'Editar' }, nav);
  if (process.platform !== 'darwin') template.push({ label: 'Salir', role: 'quit' });
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

app.whenReady().then(() => {
  // Permitir ubicación y pantalla completa solo al visor
  session.defaultSession.setPermissionRequestHandler((wc, permission, cb, details) => {
    const ok = ['geolocation', 'fullscreen', 'clipboard-sanitized-write'].includes(permission);
    cb(ok && isInternal(details.requestingUrl || wc.getURL()));
  });
  buildMenu();
  createWindow();
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});

app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
