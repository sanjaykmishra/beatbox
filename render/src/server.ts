import express from 'express';

const app = express();
app.use(express.json({ limit: '4mb' }));

const PORT = Number(process.env.RENDER_PORT ?? 3000);
const SHARED_TOKEN = process.env.RENDER_SERVICE_TOKEN ?? '';

function requireServiceToken(
  req: express.Request,
  res: express.Response,
  next: express.NextFunction,
): void {
  if (!SHARED_TOKEN) {
    next();
    return;
  }
  const header = req.header('x-render-token');
  if (header !== SHARED_TOKEN) {
    res.status(401).json({ error: 'unauthorized' });
    return;
  }
  next();
}

app.get('/healthz', (_req, res) => {
  res.json({ status: 'ok', time: new Date().toISOString() });
});

app.post('/render', requireServiceToken, (_req, res) => {
  // Implemented in week 6 — see docs/08-build-plan.md.
  res.status(501).json({ error: 'not_implemented' });
});

app.post('/screenshot', requireServiceToken, (_req, res) => {
  // Implemented in week 3 — see docs/08-build-plan.md.
  res.status(501).json({ error: 'not_implemented' });
});

app.listen(PORT, () => {
  // eslint-disable-next-line no-console
  console.log(JSON.stringify({ msg: 'render_service_listening', port: PORT }));
});
