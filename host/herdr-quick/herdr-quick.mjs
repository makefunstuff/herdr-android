#!/usr/bin/env node
// version: 1
// herdr-quick: runs one AI quick command of Herdr for Android as a durable worker (pi-durable).
//
//   node herdr-quick.mjs run <run folder>
//
// The app writes the run folder (~/.local/state/herdr-android/quick/<id>/) with `request.json`:
//   {"prompt": "…", "model": "pi/deepseek/deepseek-flash", "tools": ["read-only"], "cwd": "/repo",
//    "timeoutSeconds": 600, "maxTokens": 200000}
// and starts this detached from its SSH connection. The conversation lives in that folder's
// `durable.sqlite`, so the phone can go away, and a worker that was killed (or a machine that
// restarted) is started again by the app and carries on where it stopped (pi-durable's resume).
//
// What it writes there, for the app to read:
//   status.json   {"state": "setup|running|done|failed|stopped|limit", "activity": "Reading src/a.kt",
//                  "tokens": 1234, "model": "…", "started": <s>, "updated": <s>, "ended": <s>, "error": "…"}
//   answer.md     the answer so far, then the whole answer
//   worker.pid    this process (the app knows the run by `pid`, its detached job's)
// It stops on SIGTERM, or when a file `stop` appears, and at the run's time or token limit.
//
// Models: `claude/<id>` is Anthropic's (ANTHROPIC_API_KEY, or the key in ~/.pi/agent/auth.json);
// `pi/<provider>/<id>` is a provider of pi's own ~/.pi/agent/models.json (or a built-in one) with
// pi's keys; no model: pi's default (~/.pi/agent/settings.json). It never picks one by itself.
import {execFile} from 'node:child_process';
import {existsSync, readFileSync, renameSync, writeFileSync} from 'node:fs';
import {readdir, stat} from 'node:fs/promises';
import {homedir, hostname} from 'node:os';
import {isAbsolute, join, relative, resolve} from 'node:path';
import {promisify} from 'node:util';
import {BACKGROUND_CONTEXT as C} from '@earendil-works/chord/context';
import {Type, createProvider, InMemoryCredentialStore} from '@earendil-works/pi-ai';
import {builtinModels} from '@earendil-works/pi-ai/providers/all';
import {openAICompletionsApi} from '@earendil-works/pi-ai/api/openai-completions.lazy';
import {anthropicMessagesApi} from '@earendil-works/pi-ai/api/anthropic-messages.lazy';
import {AssistantEntry, configure, createRegistry, defineDoc, defineExtension, defineTool, Harness, LiveDoc, UsageDoc} from '@earendil-works/pi-durable';
import {CodingTools, createReadTool} from '@earendil-works/pi-durable/tools';
import {NodeExecutionEnv} from '@earendil-works/pi-durable/env/node';
import {openNodeSqliteStorage} from '@earendil-works/pi-durable/storage/sqlite/node';

const exec = promisify(execFile);
const PI = process.env.PI_AGENT_DIR || join(homedir(), '.pi', 'agent');
const MAX_ANSWER = 256 * 1024;
const DEFAULT_TIMEOUT = 600;
const DEFAULT_TOKENS = 200_000;
const now = () => Math.floor(Date.now() / 1000);
const readJson = (path, fallback) => { try { return JSON.parse(readFileSync(path, 'utf8')); } catch { return fallback; } };

/** Writes [value] to [path] in one step, so the app never reads half of it. */
function writeAtomic(path, value) {
  const tmp = `${path}.tmp`;
  writeFileSync(tmp, value);
  renameSync(tmp, path);
}

// ---- models and keys ------------------------------------------------------------------------------

/** A value from pi's models.json: `$VAR`/`${VAR}` from the environment, `!command` run, else itself. */
async function resolveValue(value) {
  if (typeof value !== 'string') return undefined;
  if (value.startsWith('!')) {
    const {stdout} = await exec('/bin/sh', ['-c', value.slice(1)], {timeout: 10_000, maxBuffer: 65536});
    return stdout.trim() || undefined;
  }
  return value.replace(/\$\{([A-Za-z_][A-Za-z0-9_]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)/g, (_, a, b) => process.env[a || b] ?? '');
}

/** pi's keys (~/.pi/agent/auth.json), read-only: `{provider: {type: "api_key", key}}`. */
function piCredentials() {
  const store = new InMemoryCredentialStore();
  const auth = readJson(join(PI, 'auth.json'), {});
  for (const [id, credential] of Object.entries(auth)) {
    if (credential?.type === 'api_key' && typeof credential.key === 'string') store.credentials.set(id, {type: 'api_key', key: credential.key});
  }
  return store;
}

/** `{models, ref: {provider, modelId}}` for a `# model:` value, or an error that says what is missing. */
export function chooseModel(spec) {
  const models = builtinModels({credentials: piCredentials()});
  let text = String(spec ?? '').trim();
  if (!text) {
    const settings = readJson(join(PI, 'settings.json'), {});
    if (!settings.defaultProvider || !settings.defaultModel) {
      throw new Error('No model: give the command a "# model:" line (claude/<model> or pi/<provider>/<model>)');
    }
    text = `pi/${settings.defaultProvider}/${settings.defaultModel}`;
  }
  const [agent, ...rest] = text.split('/');
  let provider, modelId;
  if (agent === 'claude') { provider = 'anthropic'; modelId = rest.join('/'); }
  else if (agent === 'pi') { provider = rest[0]; modelId = rest.slice(1).join('/'); }
  else { provider = agent; modelId = rest.join('/'); }
  if (!provider || !modelId) throw new Error(`"${text}" isn't a model: write claude/<model> or pi/<provider>/<model>`);
  const custom = readJson(join(PI, 'models.json'), {}).providers?.[provider];
  if (custom) models.setProvider(customProvider(provider, custom, modelId));
  else if (provider === 'anthropic' && !models.getModel?.(provider, modelId)) models.setProvider(anthropicLike(provider, modelId));
  return {models, ref: {provider, modelId}, label: text};
}

/** A provider from pi's models.json (OpenAI-compatible or Anthropic messages), with only [modelId]. */
function customProvider(id, config, modelId) {
  const model = config.models?.find(m => m.id === modelId) ?? {id: modelId};
  const api = model.api || config.api || 'openai-completions';
  if (api !== 'openai-completions' && api !== 'anthropic-messages') throw new Error(`${id} uses ${api}, which quick commands can't talk to yet`);
  const baseUrl = model.baseUrl || config.baseUrl;
  const stored = readJson(join(PI, 'auth.json'), {})[id];
  return createProvider({
    id, name: id, baseUrl,
    auth: {apiKey: {
      name: `${id} (pi's models.json)`,
      resolve: async () => {
        const key = (await resolveValue(config.apiKey)) || (stored?.type === 'api_key' ? stored.key : undefined);
        if (!key) throw new Error(`No key for ${id}: pi has none in auth.json or models.json`);
        const headers = {};
        for (const [k, v] of Object.entries({...config.headers, ...model.headers})) headers[k] = await resolveValue(v);
        return {auth: {apiKey: key, headers}, source: 'pi'};
      },
    }},
    models: [{
      ...model, id: modelId, name: model.name || modelId, api, provider: id, baseUrl,
      reasoning: model.reasoning ?? false, input: model.input || ['text'],
      contextWindow: model.contextWindow || 131072, maxTokens: model.maxTokens || 16384,
      cost: model.cost || {input: 0, output: 0, cacheRead: 0, cacheWrite: 0},
      compat: {...config.compat, ...model.compat}, headers: undefined,
    }],
    api: api === 'anthropic-messages' ? anthropicMessagesApi() : openAICompletionsApi(),
  });
}

/** An Anthropic model pi-ai's list doesn't have yet (a newer one), on Anthropic's API. */
function anthropicLike(id, modelId) {
  return createProvider({
    id, name: 'Anthropic', baseUrl: 'https://api.anthropic.com',
    auth: {apiKey: {name: 'Anthropic API key', resolve: async ({credential}) => {
      const key = credential?.key || process.env.ANTHROPIC_API_KEY;
      if (!key) throw new Error('No Anthropic key: set ANTHROPIC_API_KEY or log in to anthropic in pi');
      return {auth: {apiKey: key}, source: 'pi'};
    }}},
    models: [{id: modelId, name: modelId, api: 'anthropic-messages', provider: id, baseUrl: 'https://api.anthropic.com', reasoning: true,
      input: ['text'], contextWindow: 200000, maxTokens: 32000, cost: {input: 0, output: 0, cacheRead: 0, cacheWrite: 0}}],
    api: anthropicMessagesApi(),
  });
}

// ---- tools ----------------------------------------------------------------------------------------

const text = value => ({content: [{type: 'text', text: value}]});
const cut = (value, n = 60_000) => value.length > n ? value.slice(0, n) + `\n… (${value.length - n} more characters)` : value;

/** Reading only: files, folder listings, a search, and Git's history and diffs. Nothing changes on the machine. */
function readOnlyTools(cwd) {
  const inside = path => {
    const target = resolve(cwd, path ?? '.');
    const rel = relative(cwd, target);
    if (rel.startsWith('..') || isAbsolute(rel)) throw new Error('Stay inside the folder the command runs in');
    return target;
  };
  const GIT = new Set(['log', 'show', 'diff', 'status', 'blame', 'branch', 'rev-parse', 'ls-files', 'grep', 'shortlog', 'describe', 'tag', 'remote', 'merge-base', 'rev-list', 'reflog']);
  return defineExtension({
    name: 'quick-read',
    tools: [
      createReadTool(),
      defineTool({
        name: 'ls', description: 'List a folder (relative to the working folder): names, a / after folders.', replay: 'safe',
        parameters: Type.Object({path: Type.Optional(Type.String())}),
        execute: async ({path}) => {
          const entries = await readdir(inside(path), {withFileTypes: true});
          return text(entries.map(e => e.name + (e.isDirectory() ? '/' : '')).sort().join('\n') || '(empty)');
        },
      }),
      defineTool({
        name: 'grep', description: 'Search the files for a regular expression (git grep in a repository, else grep -r). Lines as path:line:text.', replay: 'safe',
        parameters: Type.Object({pattern: Type.String(), path: Type.Optional(Type.String()), ignoreCase: Type.Optional(Type.Boolean())}),
        execute: async ({pattern, path, ignoreCase}) => {
          const where = inside(path);
          const flags = ignoreCase ? ['-i'] : [];
          const run = async (cmd, args) => (await exec(cmd, args, {cwd, maxBuffer: 8 << 20, timeout: 60_000}).catch(e => e)).stdout ?? '';
          let out = await run('git', ['-c', 'core.quotepath=off', 'grep', '-n', '-I', '-E', ...flags, '-e', pattern, '--', where]);
          if (!out) out = await run('grep', ['-rnIE', ...flags, '--exclude-dir=.git', '--exclude-dir=node_modules', '-e', pattern, where]);
          return text(cut(out.split('\n').slice(0, 400).join('\n')) || 'No match');
        },
      }),
      defineTool({
        name: 'git', description: `Run a read-only git command in the working folder: ${[...GIT].join(', ')}. Pass its arguments as a list.`, replay: 'safe',
        parameters: Type.Object({args: Type.Array(Type.String())}),
        execute: async ({args}) => {
          if (!GIT.has(args[0])) throw new Error(`Only ${[...GIT].join(', ')}`);
          if (args.some(a => /^--(output|exec|upload-pack|receive-pack)/.test(a) || a === '-o')) throw new Error('That option writes or runs something');
          const r = await exec('git', ['-c', 'color.ui=false', '-c', 'core.pager=cat', ...args], {
            cwd, maxBuffer: 16 << 20, timeout: 60_000, env: {...process.env, GIT_OPTIONAL_LOCKS: '0', GIT_PAGER: 'cat', GIT_TERMINAL_PROMPT: '0'},
          }).catch(e => e);
          return text(cut((r.stdout ?? '') + (r.stderr ? `\n${r.stderr}` : '')) || '(no output)');
        },
      }),
    ],
  });
}

const decode = s => s.replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#x27;|&#39;/g, "'").replace(/&nbsp;/g, ' ');
const htmlToText = html => decode(html.replace(/<(script|style|noscript|svg|head)[\s\S]*?<\/\1>/gi, ' ')
  .replace(/<\/(p|div|section|article|li|h[1-6]|tr|pre)>|<br\s*\/?>/gi, '\n').replace(/<[^>]+>/g, ' '))
  .replace(/[ \t]+/g, ' ').replace(/\n\s*\n+/g, '\n\n').trim();

/** The web: a search (DuckDuckGo's HTML page, no key) and fetching a page as text. What comes back is untrusted. */
const WebTools = defineExtension({
  name: 'quick-web',
  tools: [
    defineTool({
      name: 'web_search', description: 'Search the web: titles, links and snippets (untrusted text). Read one with web_fetch.', replay: 'safe',
      parameters: Type.Object({query: Type.String({maxLength: 400})}),
      execute: async ({query}, _api, ctx) => {
        const r = await fetch(`https://html.duckduckgo.com/html/?q=${encodeURIComponent(query)}`, {signal: ctx?.abortSignal, headers: {'user-agent': 'herdr-quick/1'}});
        const html = await r.text();
        const out = [];
        for (const m of html.matchAll(/<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>([\s\S]*?)<\/a>[\s\S]*?(?:class="result__snippet"[^>]*>([\s\S]*?)<\/a>)?/g)) {
          let href = decode(m[1]);
          const u = /[?&]uddg=([^&]+)/.exec(href);
          if (u) href = decodeURIComponent(u[1]);
          out.push(`${htmlToText(m[2])}\n${href}\n${htmlToText(m[3] ?? '')}`);
          if (out.length >= 8) break;
        }
        return text(out.join('\n\n') || 'No results');
      },
    }),
    defineTool({
      name: 'web_fetch', description: 'Fetch a web page (http or https) as plain text (untrusted).', replay: 'safe',
      parameters: Type.Object({url: Type.String()}),
      execute: async ({url}, _api, ctx) => {
        const target = new URL(url);
        if (!['http:', 'https:'].includes(target.protocol)) throw new Error('Only http(s) links');
        const r = await fetch(target, {signal: ctx?.abortSignal, redirect: 'follow', headers: {'user-agent': 'herdr-quick/1'}});
        const body = (await r.text()).slice(0, 2 << 20);
        return text(cut(`HTTP ${r.status} ${r.url}\n\n` + (/html/.test(r.headers.get('content-type') ?? '') ? htmlToText(body) : body)));
      },
    }),
  ],
});

/** What a command may use, from its `# tools:` line: read-only (the default), shell (and writing files), web. */
export function toolsFor(kinds, cwd) {
  const set = new Set((kinds?.length ? kinds : ['read-only']).map(k => String(k).trim().toLowerCase()));
  const extensions = [];
  if (set.has('shell') || set.has('write') || set.has('bash')) extensions.push(CodingTools);
  else extensions.push(readOnlyTools(cwd));
  if (set.has('web') || set.has('web-search') || set.has('web search')) extensions.push(WebTools);
  return extensions;
}

// ---- the run --------------------------------------------------------------------------------------

const Run = defineDoc({kind: 'herdr.quick', version: 1, scope: 'session', initial: () => ({})});

/** What the answer says: the text blocks of an assistant message. */
const textOf = message => (message?.content ?? []).filter(b => b.type === 'text').map(b => b.text).join('');

/** What it is doing now, in words, from the live view of the run. */
function activityOf(live) {
  const tool = live?.tools?.find(t => t.status === 'running') ?? live?.tools?.find(t => t.status === 'pending');
  if (tool) return {read: 'Reading', ls: 'Looking in a folder', grep: 'Searching', git: 'Asking Git', bash: 'Running a command',
    write: 'Writing a file', edit: 'Editing a file', web_search: 'Searching the web', web_fetch: 'Reading a web page'}[tool.name] ?? `Using ${tool.name}`;
  if (live?.generation?.retry) return 'Waiting to try again';
  if (live?.generation) return textOf(live.generation.message) ? 'Writing' : 'Thinking';
  return 'Working';
}

const tokensOf = usage => Object.values(usage?.models ?? {}).reduce((n, u) => n + (u.input ?? 0) + (u.output ?? 0) + (u.cacheRead ?? 0) + (u.cacheWrite ?? 0), 0);

/** Why a run failed, in words: the provider's own message where it gave one. */
export function reasonOf(record, model) {
  const detail = String(record?.detail ?? '');
  const message = /"message"\s*:\s*"((?:[^"\\]|\\.)*)"/.exec(detail)?.[1]?.replace(/\\"/g, '"');
  if (message) return message.replace(/\s*\(request_id: [^)]*\)/, '');
  if (detail) return detail.slice(0, 400);
  return {no_model: `${model} isn't a model this machine knows`, model_error: `${model} refused the request`}[record?.reason]
    ?? record?.reason ?? 'It stopped';
}

async function run(dir) {
  const request = readJson(join(dir, 'request.json'), null);
  if (!request?.prompt) throw new Error('request.json is missing or has no prompt');
  const statusPath = join(dir, 'status.json');
  const before = readJson(statusPath, {});
  if (['done', 'failed', 'stopped', 'limit'].includes(before.state)) return; // finished already
  const status = {...before, state: 'running', started: before.started ?? now(), host: hostname()};
  const save = (change = {}) => { Object.assign(status, change, {updated: now()}); writeAtomic(statusPath, JSON.stringify(status) + '\n'); };
  writeFileSync(join(dir, 'worker.pid'), `${process.pid}\n`);
  const cwd = request.cwd && existsSync(request.cwd) ? request.cwd : homedir();
  let chosen;
  try {
    chosen = chooseModel(request.model);
  } catch (e) {
    return save({state: 'failed', error: e.message, ended: now()});
  }
  save({model: chosen.label, activity: 'Starting'});

  const registry = createRegistry();
  const extensions = toolsFor(request.tools, cwd);
  for (const extension of extensions) registry.install(extension);
  const harness = await Harness.open(await openNodeSqliteStorage(join(dir, 'durable.sqlite')), {
    models: chosen.models, registry,
    env: () => new NodeExecutionEnv({cwd}),
    settings: {retry: {maxRetries: 2}, progress: {partialIntervalMs: 400, outputIntervalMs: 800}},
  }, C);
  const instructions = [
    'You run as a quick command that someone started from the herdr Android app, on their phone.',
    `Machine: ${hostname()}. Working folder: ${cwd}.`,
    'Do the task below with the tools you have, then answer in Markdown: short, the result first, readable on a phone.',
    'Nobody can answer questions while you work: decide sensibly and say what you assumed.',
  ].join('\n');
  // One conversation per run, created once: a restarted worker finds it in the run's own document.
  const conversationId = await harness.commit(async tx => {
    const doc = await tx.doc(Run);
    if (doc.conversation !== undefined) return doc.conversation;
    const conversation = await tx.createConversation({ownership: {kind: 'ownerless'}});
    await configure(tx, conversation.id, {model: chosen.ref, instructions, extensions, cwd, thinkingLevel: request.thinking ?? 'low'});
    doc.conversation = conversation.id;
    return conversation.id;
  }, C);
  const conversation = await harness.conversation(conversationId, C);
  harness.resume();
  const submission = await conversation.submit({type: 'input', content: request.prompt, requestId: 'quick'}, C);

  const timeout = Number(request.timeoutSeconds) > 0 ? Number(request.timeoutSeconds) : DEFAULT_TIMEOUT;
  const maxTokens = Number(request.maxTokens) > 0 ? Number(request.maxTokens) : DEFAULT_TOKENS;
  let ending;
  const stop = (state, error) => {
    if (ending) return;
    ending = {state, error};
    conversation.abort(C).catch(() => {});
  };
  process.on('SIGTERM', () => stop('stopped'));
  process.on('SIGINT', () => stop('stopped'));
  let lastAnswer = '';
  const look = async () => {
    const live = await harness.snapshot(LiveDoc, conversationId, C).catch(() => undefined);
    const usage = await harness.snapshot(UsageDoc, conversationId, C).catch(() => undefined);
    const partial = textOf(live?.generation?.message);
    if (partial && partial !== lastAnswer) { lastAnswer = partial; writeAtomic(join(dir, 'answer.md'), partial.slice(0, MAX_ANSWER)); }
    const tokens = tokensOf(usage);
    if (!ending) save({activity: activityOf(live), tokens});
    if (existsSync(join(dir, 'stop'))) stop('stopped');
    else if (now() - status.started > timeout) stop('limit', `Stopped at its time limit (${Math.round(timeout / 60)} min)`);
    else if (tokens > maxTokens) stop('limit', `Stopped at its token limit (${maxTokens.toLocaleString('en')})`);
  };
  const timer = setInterval(() => { look().catch(() => {}); }, 700);
  let record;
  try {
    record = await submission.wait(C);
  } finally {
    clearInterval(timer);
  }
  await look().catch(() => {});
  let answer = '';
  if (record?.answer !== undefined) {
    const entry = await conversation.commit(tx => tx.entry(AssistantEntry, record.answer), C).catch(() => undefined);
    answer = (entry?.model ?? []).map(textOf).join('') || textOf(entry);
  }
  if (!answer) {
    // An aborted or failed run: what it had written so far, from the last answer it gave in the conversation.
    answer = lastAnswer;
  }
  if (answer) writeAtomic(join(dir, 'answer.md'), answer.slice(0, MAX_ANSWER));
  const usage = await harness.snapshot(UsageDoc, conversationId, C).catch(() => undefined);
  const failed = record?.status !== 'done';
  const error = ending ? ending.error : failed ? reasonOf(record, chosen.label) : undefined;
  save({state: ending?.state ?? (failed ? 'failed' : 'done'), activity: undefined, tokens: tokensOf(usage), ended: now(), ...(error ? {error: String(error)} : {})});
  await harness.close(C).catch(() => {});
}

const [command, dir] = process.argv.slice(2);
if (command === 'run' && dir) {
  run(resolve(dir)).then(() => process.exit(0), e => {
    try {
      const path = join(resolve(dir), 'status.json');
      const s = readJson(path, {});
      writeAtomic(path, JSON.stringify({...s, state: 'failed', error: e?.message ?? String(e), ended: now(), updated: now()}) + '\n');
    } catch {}
    console.error(e);
    process.exit(1);
  });
} else if (command === 'version') {
  console.log('1');
} else {
  console.error('usage: herdr-quick.mjs run <run folder>');
  process.exit(2);
}
