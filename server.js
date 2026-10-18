// OIOIOI prototype server. No dependencies: `node server.js`, then open http://localhost:8080
// Stores hazards in data/hazards.json and photos in data/photos/. Serves the app from public/.
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PORT = Number(process.env.PORT) || 8080;
const ROOT = __dirname;
const PUBLIC = path.join(ROOT, 'public');
const DATA = process.env.DATA_DIR || path.join(ROOT, 'data');
const DB_FILE = path.join(DATA, 'hazards.json');
const PHOTOS = path.join(DATA, 'photos');
const TYPES = ['pothole', 'debris', 'flooding', 'accident', 'other'];
const MAX_BODY = 2 * 1024 * 1024;

fs.mkdirSync(PHOTOS, { recursive: true });
let hazards = fs.existsSync(DB_FILE) ? JSON.parse(fs.readFileSync(DB_FILE, 'utf8')) : [];

function save() {
  const tmp = DB_FILE + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(hazards, null, 1));
  fs.renameSync(tmp, DB_FILE);
}

// Votes are maps of userId -> timestamp; 0 means the vote was withdrawn.
const count = (votes) => Object.values(votes || {}).filter((t) => t > 0).length;
function status(h) {
  const need = Math.max(1, Math.ceil(count(h.reporters) / 2));
  return count(h.clears) >= need ? 'fixed' : 'active';
}
const withStatus = (h) => ({ ...h, status: status(h) });

function send(res, code, body, type = 'application/json') {
  res.writeHead(code, { 'Content-Type': type, 'Cache-Control': 'no-store' });
  res.end(type === 'application/json' ? JSON.stringify(body) : body);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY) { reject(new Error('too large')); req.destroy(); }
      else chunks.push(c);
    });
    req.on('end', () => {
      try { resolve(chunks.length ? JSON.parse(Buffer.concat(chunks).toString()) : {}); }
      catch (e) { reject(e); }
    });
  });
}

const validUid = (u) => typeof u === 'string' && /^[\w-]{6,64}$/.test(u);
const esc = (s) => String(s).replace(/[<>&"']/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;', "'": '&apos;' }[c]));

function geojson(list, base) {
  return {
    type: 'FeatureCollection',
    features: list.map((h) => ({
      type: 'Feature',
      geometry: { type: 'Point', coordinates: [h.lng, h.lat] },
      properties: {
        id: h.id, type: h.type, status: status(h), note: h.note || '',
        reported_at: new Date(h.createdAt).toISOString(),
        reporters: count(h.reporters), clear_votes: count(h.clears),
        photo_url: h.hasPhoto ? `${base}/api/photos/${h.id}` : null,
      },
    })),
  };
}

function kml(list, base) {
  const marks = list.map((h) => `  <Placemark>
    <name>${esc(h.type)} (${count(h.reporters)} reporters)</name>
    <styleUrl>#${status(h)}</styleUrl>
    <description>${esc(`Status: ${status(h)}. Reported ${new Date(h.createdAt).toISOString()}. ${h.note || ''}`)}${h.hasPhoto ? esc(` Photo: ${base}/api/photos/${h.id}`) : ''}</description>
    <ExtendedData>
      <Data name="status"><value>${status(h)}</value></Data>
      <Data name="reporters"><value>${count(h.reporters)}</value></Data>
      <Data name="clear_votes"><value>${count(h.clears)}</value></Data>
    </ExtendedData>
    <Point><coordinates>${h.lng},${h.lat},0</coordinates></Point>
  </Placemark>`).join('\n');
  return `<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2"><Document>
  <name>OIOIOI road hazards</name>
  <Style id="active"><IconStyle><color>ff0000ff</color><Icon><href>https://maps.google.com/mapfiles/kml/shapes/caution.png</href></Icon></IconStyle></Style>
  <Style id="fixed"><IconStyle><color>ff888888</color><Icon><href>https://maps.google.com/mapfiles/kml/shapes/placemark_circle.png</href></Icon></IconStyle></Style>
${marks}
</Document></kml>`;
}

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.json': 'application/json', '.webmanifest': 'application/manifest+json', '.svg': 'image/svg+xml', '.png': 'image/png', '.css': 'text/css' };

async function api(req, res, url) {
  const parts = url.pathname.split('/').filter(Boolean); // ['api', 'hazards', id, action]
  const base = `${req.headers['x-forwarded-proto'] || 'http'}://${req.headers.host}`;

  if (req.method === 'GET' && url.pathname === '/api/hazards') return send(res, 200, hazards.map(withStatus));

  if (req.method === 'GET' && (url.pathname === '/api/export.geojson' || url.pathname === '/api/export.kml')) {
    const list = url.searchParams.get('all') ? hazards : hazards.filter((h) => status(h) === 'active');
    return url.pathname.endsWith('.kml')
      ? send(res, 200, kml(list, base), 'application/vnd.google-earth.kml+xml')
      : send(res, 200, JSON.stringify(geojson(list, base)), 'application/geo+json');
  }

  if (req.method === 'GET' && parts[1] === 'photos' && /^[\w-]+$/.test(parts[2] || '')) {
    const file = path.join(PHOTOS, parts[2] + '.jpg');
    if (!fs.existsSync(file)) return send(res, 404, { error: 'no photo' });
    res.writeHead(200, { 'Content-Type': 'image/jpeg' });
    return fs.createReadStream(file).pipe(res);
  }

  if (req.method !== 'POST') return send(res, 404, { error: 'not found' });
  let body;
  try { body = await readBody(req); } catch { return send(res, 400, { error: 'bad body' }); }
  if (!validUid(body.uid)) return send(res, 400, { error: 'uid required' });
  const now = Date.now();

  if (url.pathname === '/api/hazards') {
    const { lat, lng } = body;
    if (typeof lat !== 'number' || typeof lng !== 'number' || Math.abs(lat) > 90 || Math.abs(lng) > 180) return send(res, 400, { error: 'lat/lng required' });
    const h = {
      id: crypto.randomUUID(), type: TYPES.includes(body.type) ? body.type : 'pothole',
      lat, lng, accuracy: Number(body.accuracy) || null, heading: Number.isFinite(body.heading) ? body.heading : null,
      note: String(body.note || '').slice(0, 280), createdAt: now, createdBy: body.uid,
      reporters: { [body.uid]: now }, clears: {}, hasPhoto: false,
    };
    if (typeof body.photo === 'string' && body.photo.startsWith('data:image/jpeg;base64,')) {
      fs.writeFileSync(path.join(PHOTOS, h.id + '.jpg'), Buffer.from(body.photo.split(',')[1], 'base64'));
      h.hasPhoto = true;
    }
    hazards.push(h);
    save();
    return send(res, 201, withStatus(h));
  }

  const h = parts[1] === 'hazards' && hazards.find((x) => x.id === parts[2]);
  if (!h) return send(res, 404, { error: 'not found' });
  if (parts[3] === 'confirm') { h.reporters[body.uid] = now; if (h.clears[body.uid]) h.clears[body.uid] = 0; }
  else if (parts[3] === 'clear') { h.clears[body.uid] = now; }
  else return send(res, 404, { error: 'not found' });
  save();
  return send(res, 200, withStatus(h));
}

http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  try {
    if (url.pathname.startsWith('/api/')) return await api(req, res, url);
    const rel = url.pathname === '/' ? 'index.html' : decodeURIComponent(url.pathname).replace(/^\/+/, '');
    const file = path.join(PUBLIC, rel);
    if (!file.startsWith(PUBLIC) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) return send(res, 404, 'Not found', 'text/plain');
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' });
    fs.createReadStream(file).pipe(res);
  } catch (e) {
    console.error(e);
    send(res, 500, { error: 'server error' });
  }
}).listen(PORT, () => console.log(`OIOIOI prototype on http://localhost:${PORT}`));
