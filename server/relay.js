// Orbit Cast relay: pairs a sender and a receiver by room code and forwards their
// (already end-to-end encrypted) messages. It never sees the PIN or the video.
const http = require('http');
const { WebSocketServer } = require('ws');

const rooms = new Map(); // room -> { sender, receiver }
const server = http.createServer((req, res) => res.end('orbit relay ok'));
const wss = new WebSocketServer({ server, maxPayload: 32 * 1024 * 1024 });

wss.on('connection', (ws, req) => {
  const m = /^\/([A-Za-z0-9]{4,16})\/(sender|receiver)$/.exec(req.url || '');
  if (!m) return ws.close();
  const [, room, role] = m;
  const other = role === 'sender' ? 'receiver' : 'sender';
  const r = rooms.get(room) || {};
  if (r[role]) r[role].close();
  r[role] = ws;
  rooms.set(room, r);

  ws.on('message', (data) => {
    const peer = rooms.get(room)?.[other];
    if (!peer || peer.readyState !== 1) return;
    if (peer.bufferedAmount > 4e6 && data[0] === 4) return; // drop delta frames if peer is slow
    peer.send(data, { binary: true });
  });

  ws.on('close', () => {
    const x = rooms.get(room);
    if (!x || x[role] !== ws) return;
    delete x[role];
    const peer = x[other];
    if (peer) peer.close(); else rooms.delete(room);
  });
});

server.listen(process.env.PORT || 8080, () => console.log('relay listening'));
