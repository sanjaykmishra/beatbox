import express from 'express';
import puppeteer, { type Browser } from 'puppeteer';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import Handlebars from 'handlebars';

const __dirname = dirname(fileURLToPath(import.meta.url));
// dist/server.js → templates/ at ../templates relative to dist; src/server.ts → ../templates from src.
const TEMPLATES_DIR = resolve(__dirname, '../templates');

const STANDARD_HBS = readFileSync(resolve(TEMPLATES_DIR, 'standard.hbs'), 'utf-8');
const standardTemplate = Handlebars.compile(STANDARD_HBS);

const app = express();
app.use(express.json({ limit: '4mb' }));

const PORT = Number(process.env.RENDER_PORT ?? 3000);
const SHARED_TOKEN = process.env.RENDER_SERVICE_TOKEN ?? '';
const EXECUTABLE_PATH = process.env.PUPPETEER_EXECUTABLE_PATH || undefined;
const LOG_LEVEL = (process.env.RENDER_LOG_LEVEL ?? 'info').toLowerCase();
const LEVEL_RANK: Record<string, number> = { debug: 10, info: 20, warn: 30, error: 40 };
const ACTIVE_RANK = LEVEL_RANK[LOG_LEVEL] ?? LEVEL_RANK.info;

function log(
  level: 'debug' | 'info' | 'warn' | 'error',
  msg: string,
  fields: Record<string, unknown> = {},
): void {
  if (LEVEL_RANK[level] < ACTIVE_RANK) return;
  // eslint-disable-next-line no-console
  console.log(
    JSON.stringify({ level, msg, time: new Date().toISOString(), ...fields }),
  );
}

// One JSON access line per non-healthz request: method, path, status, duration, response size.
// Healthz is hammered by Docker every 5s — would drown the log otherwise.
app.use((req, res, next) => {
  if (req.path === '/healthz') return next();
  const started = Date.now();
  res.on('finish', () => {
    log(res.statusCode >= 500 ? 'error' : res.statusCode >= 400 ? 'warn' : 'info', 'request', {
      method: req.method,
      path: req.path,
      status: res.statusCode,
      duration_ms: Date.now() - started,
      bytes: Number(res.getHeader('content-length')) || undefined,
    });
  });
  next();
});

let browserPromise: Promise<Browser> | null = null;

function getBrowser(): Promise<Browser> {
  if (!browserPromise) {
    log('info', 'puppeteer_launching', { executablePath: EXECUTABLE_PATH ?? 'bundled' });
    browserPromise = puppeteer
      .launch({
        headless: true,
        executablePath: EXECUTABLE_PATH,
        args: ['--no-sandbox', '--disable-dev-shm-usage'],
      })
      .then(
        (b) => {
          log('info', 'puppeteer_launched');
          return b;
        },
        (e) => {
          log('error', 'puppeteer_launch_failed', { error: errorFields(e) });
          browserPromise = null;
          throw e;
        },
      );
  }
  return browserPromise;
}

function errorFields(e: unknown): Record<string, unknown> {
  if (e instanceof Error) {
    return {
      name: e.name,
      message: e.message,
      stack: e.stack?.split('\n').slice(0, 6).join('\n'),
    };
  }
  return { message: String(e) };
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

function renderHtml(payload: unknown): string {
  return standardTemplate(payload);
}

app.post('/preview', requireServiceToken, (req, res) => {
  try {
    const html = renderHtml(req.body);
    log('debug', 'preview_rendered', { html_bytes: Buffer.byteLength(html) });
    res.setHeader('Content-Type', 'text/html; charset=utf-8');
    res.send(html);
  } catch (err) {
    log('warn', 'preview_failed', { error: errorFields(err) });
    const message = err instanceof Error ? err.message : 'unknown';
    res.status(400).json({ error: 'render_failed', detail: message });
  }
});

app.post('/render', requireServiceToken, async (req, res) => {
  let page: Awaited<ReturnType<Browser['newPage']>> | null = null;
  const reportId = (req.body?.report?.id as string | undefined) ?? null;
  const startedAt = Date.now();
  try {
    const html = renderHtml(req.body);
    log('debug', 'render_html_built', { report_id: reportId, html_bytes: Buffer.byteLength(html) });
    const browser = await getBrowser();
    page = await browser.newPage();
    page.on('pageerror', (e) =>
      log('warn', 'render_page_error', { report_id: reportId, error: errorFields(e) }),
    );
    page.on('requestfailed', (r) =>
      log('debug', 'render_request_failed', {
        report_id: reportId,
        url: r.url(),
        reason: r.failure()?.errorText,
      }),
    );
    await page.setContent(html, { waitUntil: 'networkidle0', timeout: 30_000 });
    const pdf = await page.pdf({
      format: 'Letter',
      printBackground: true,
      margin: { top: '0.5in', right: '0.5in', bottom: '0.5in', left: '0.5in' },
    });
    log('info', 'render_done', {
      report_id: reportId,
      pdf_bytes: pdf.length,
      duration_ms: Date.now() - startedAt,
    });
    res.setHeader('Content-Type', 'application/pdf');
    res.send(Buffer.from(pdf));
  } catch (err) {
    log('error', 'render_failed', {
      report_id: reportId,
      duration_ms: Date.now() - startedAt,
      error: errorFields(err),
    });
    const message = err instanceof Error ? err.message : 'unknown';
    res.status(502).json({ error: 'render_failed', detail: message });
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

app.post('/screenshot', requireServiceToken, async (req, res) => {
  const url = (req.body?.url as string | undefined)?.trim();
  if (!url || !/^https?:\/\//.test(url)) {
    log('warn', 'screenshot_invalid_url', { url });
    res.status(400).json({ error: 'invalid_url' });
    return;
  }
  const viewportHeight = Number(req.body?.viewport_height ?? 800);
  let page: Awaited<ReturnType<Browser['newPage']>> | null = null;
  const startedAt = Date.now();
  try {
    const browser = await getBrowser();
    page = await browser.newPage();
    await page.setViewport({ width: 1280, height: viewportHeight, deviceScaleFactor: 1 });
    await page.setUserAgent(
      'Mozilla/5.0 (compatible; BeatBot/1.0; +https://beat.app/bot)',
    );
    log('debug', 'screenshot_navigating', { url });
    const navResponse = await page.goto(url, { waitUntil: 'networkidle2', timeout: 30_000 });
    log('debug', 'screenshot_navigated', {
      url,
      status: navResponse?.status() ?? null,
      final_url: page.url(),
    });
    const png = await page.screenshot({
      type: 'png',
      clip: { x: 0, y: 0, width: 1280, height: viewportHeight },
    });
    log('info', 'screenshot_done', {
      url,
      png_bytes: png.length,
      status: navResponse?.status() ?? null,
      duration_ms: Date.now() - startedAt,
    });
    res.setHeader('Content-Type', 'image/png');
    res.send(png);
  } catch (err) {
    log('error', 'screenshot_failed', {
      url,
      duration_ms: Date.now() - startedAt,
      error: errorFields(err),
    });
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
  log('info', 'render_service_listening', { port: PORT, log_level: LOG_LEVEL });
});

async function shutdown(signal: string) {
  log('info', 'shutting_down', { signal });
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
