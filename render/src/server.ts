import express from 'express';
import puppeteer, { type Browser } from 'puppeteer';

const app = express();
app.use(express.json({ limit: '4mb' }));

const PORT = Number(process.env.RENDER_PORT ?? 3000);
const SHARED_TOKEN = process.env.RENDER_SERVICE_TOKEN ?? '';
const EXECUTABLE_PATH = process.env.PUPPETEER_EXECUTABLE_PATH || undefined;

let browserPromise: Promise<Browser> | null = null;

function getBrowser(): Promise<Browser> {
  if (!browserPromise) {
    browserPromise = puppeteer.launch({
      headless: true,
      executablePath: EXECUTABLE_PATH,
      args: ['--no-sandbox', '--disable-dev-shm-usage'],
    });
  }
  return browserPromise;
}

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

app.post('/screenshot', requireServiceToken, async (req, res) => {
  const url = (req.body?.url as string | undefined)?.trim();
  if (!url || !/^https?:\/\//.test(url)) {
    res.status(400).json({ error: 'invalid_url' });
    return;
  }
  const viewportHeight = Number(req.body?.viewport_height ?? 800);
  let page: Awaited<ReturnType<Browser['newPage']>> | null = null;
  try {
    const browser = await getBrowser();
    page = await browser.newPage();
    await page.setViewport({ width: 1280, height: viewportHeight, deviceScaleFactor: 1 });
    await page.setUserAgent(
      'Mozilla/5.0 (compatible; BeatBot/1.0; +https://beat.app/bot)',
    );
    await page.goto(url, { waitUntil: 'networkidle2', timeout: 30_000 });
    const png = await page.screenshot({ type: 'png', clip: { x: 0, y: 0, width: 1280, height: viewportHeight } });
    res.setHeader('Content-Type', 'image/png');
    res.send(png);
  } catch (err) {
    const message = err instanceof Error ? err.message : 'unknown';
    res.status(502).json({ error: 'screenshot_failed', detail: message });
  } finally {
    if (page) {
      try {
        await page.close();
      } catch {
        /* ignore */
      }
    }
  }
});

const server = app.listen(PORT, () => {
  // eslint-disable-next-line no-console
  console.log(JSON.stringify({ msg: 'render_service_listening', port: PORT }));
});

async function shutdown(signal: string) {
  // eslint-disable-next-line no-console
  console.log(JSON.stringify({ msg: 'shutting_down', signal }));
  server.close();
  if (browserPromise) {
    try {
      const b = await browserPromise;
      await b.close();
    } catch {
      /* ignore */
    }
  }
  process.exit(0);
}

process.on('SIGINT', () => void shutdown('SIGINT'));
process.on('SIGTERM', () => void shutdown('SIGTERM'));
