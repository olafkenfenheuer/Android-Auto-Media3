<?php
/**
 * UAMP catalog admin — add/delete radio stations in music.json.
 *
 * Deploy this file next to music.json in /var/www/html/uamp/ (so __DIR__/music.json
 * resolves to the catalog the Android app downloads). The web server user (www-data)
 * needs write permission on the directory and music.json.
 *
 * The JSON schema mirrors JsonMusic in the app:
 *   { "music": [ { id, title, album, artist, genre, source, image,
 *                  trackNumber, totalTrackCount, duration, site }, ... ] }
 */
declare(strict_types=1);
session_start();

const JSON_FILE = __DIR__ . '/music.json';

/** Loads the catalog, tolerating a missing/empty file. */
function load_catalog(string $file): array
{
    if (!is_file($file)) {
        return ['music' => []];
    }
    $data = json_decode((string)file_get_contents($file), true);
    if (!is_array($data) || !isset($data['music']) || !is_array($data['music'])) {
        return ['music' => []];
    }
    return $data;
}

/** Writes the catalog atomically, keeping a single .bak of the previous version. */
function save_catalog(string $file, array $data): bool
{
    $json = json_encode(
        $data,
        JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE
    );
    if ($json === false) {
        return false;
    }
    if (is_file($file)) {
        @copy($file, $file . '.bak');
    }
    $tmp = $file . '.tmp';
    if (file_put_contents($tmp, $json . "\n", LOCK_EX) === false) {
        return false;
    }
    return rename($tmp, $file);
}

/** Builds a unique, slug-like id from a title (fallback when none is supplied). */
function generate_id(string $title, array $music): string
{
    $base = strtolower($title);
    $base = preg_replace('/[^a-z0-9]+/', '_', $base) ?? '';
    $base = trim($base, '_');
    if ($base === '') {
        $base = 'station';
    }
    $existing = array_column($music, 'id');
    $id = $base;
    $i = 1;
    while (in_array($id, $existing, true)) {
        $id = $base . '_' . (++$i);
    }
    return $id;
}

function flash(string $type, string $text): void
{
    $_SESSION['flash'] = ['type' => $type, 'text' => $text];
}

function redirect_self(): void
{
    header('Location: ' . strtok($_SERVER['REQUEST_URI'], '?'));
    exit;
}

/** Builds a station entry from the POST fields ($id/$title/$source are already validated). */
function station_from_post(string $id, string $title, string $source): array
{
    $num = static function (string $key, int $default): int {
        $raw = trim((string)($_POST[$key] ?? ''));
        return $raw === '' ? $default : (int)$raw;
    };
    return [
        'id'              => $id,
        'title'           => $title,
        'album'           => trim((string)($_POST['album'] ?? '')),
        'artist'          => trim((string)($_POST['artist'] ?? '')),
        'genre'           => trim((string)($_POST['genre'] ?? '')),
        'source'          => $source,
        'image'           => trim((string)($_POST['image'] ?? '')),
        'trackNumber'     => $num('trackNumber', 1),
        'totalTrackCount' => $num('totalTrackCount', 1),
        'duration'        => $num('duration', -1),
        'site'            => trim((string)($_POST['site'] ?? '')),
    ];
}

// ---- Handle mutations (Post/Redirect/Get to avoid resubmission) --------------
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $catalog = load_catalog(JSON_FILE);
    $music = $catalog['music'];
    $action = $_POST['action'] ?? '';

    if ($action === 'add') {
        $title = trim((string)($_POST['title'] ?? ''));
        $source = trim((string)($_POST['source'] ?? ''));

        if ($title === '' || $source === '') {
            flash('error', 'Titel und Stream-URL (source) sind Pflichtfelder.');
            redirect_self();
        }
        if (!preg_match('#^https?://#i', $source)) {
            flash('error', 'Die Stream-URL muss mit http:// oder https:// beginnen.');
            redirect_self();
        }

        $id = trim((string)($_POST['id'] ?? ''));
        if ($id === '') {
            $id = generate_id($title, $music);
        } elseif (in_array($id, array_column($music, 'id'), true)) {
            flash('error', 'Die ID "' . $id . '" existiert bereits.');
            redirect_self();
        }

        $music[] = station_from_post($id, $title, $source);

        $catalog['music'] = $music;
        $saved = save_catalog(JSON_FILE, $catalog);
        flash(
            $saved ? 'ok' : 'error',
            $saved
                ? 'Sender "' . $title . '" hinzugefügt (ID: ' . $id . ').'
                : 'Schreiben fehlgeschlagen — Dateirechte auf music.json prüfen.'
        );
        redirect_self();
    }

    if ($action === 'update') {
        $originalId = (string)($_POST['original_id'] ?? '');
        $idx = null;
        foreach ($music as $i => $m) {
            if (($m['id'] ?? '') === $originalId) {
                $idx = $i;
                break;
            }
        }
        if ($idx === null) {
            flash('error', 'Kein Sender mit ID "' . $originalId . '" gefunden.');
            redirect_self();
        }

        $title = trim((string)($_POST['title'] ?? ''));
        $source = trim((string)($_POST['source'] ?? ''));
        if ($title === '' || $source === '') {
            flash('error', 'Titel und Stream-URL (source) sind Pflichtfelder.');
            redirect_self();
        }
        if (!preg_match('#^https?://#i', $source)) {
            flash('error', 'Die Stream-URL muss mit http:// oder https:// beginnen.');
            redirect_self();
        }

        // Keep the original id if the field was cleared; otherwise ensure the new id is unique.
        $id = trim((string)($_POST['id'] ?? '')) ?: $originalId;
        foreach ($music as $i => $m) {
            if ($i !== $idx && ($m['id'] ?? '') === $id) {
                flash('error', 'Die ID "' . $id . '" existiert bereits.');
                redirect_self();
            }
        }

        $music[$idx] = station_from_post($id, $title, $source);

        $catalog['music'] = $music;
        $saved = save_catalog(JSON_FILE, $catalog);
        flash(
            $saved ? 'ok' : 'error',
            $saved
                ? 'Sender "' . $title . '" gespeichert.'
                : 'Schreiben fehlgeschlagen — Dateirechte auf music.json prüfen.'
        );
        redirect_self();
    }

    if ($action === 'delete') {
        $id = (string)($_POST['id'] ?? '');
        $before = count($music);
        $music = array_values(array_filter(
            $music,
            static fn(array $m): bool => ($m['id'] ?? '') !== $id
        ));
        if (count($music) === $before) {
            flash('error', 'Kein Sender mit ID "' . $id . '" gefunden.');
            redirect_self();
        }
        $catalog['music'] = $music;
        $saved = save_catalog(JSON_FILE, $catalog);
        flash(
            $saved ? 'ok' : 'error',
            $saved
                ? 'Sender "' . $id . '" gelöscht.'
                : 'Schreiben fehlgeschlagen — Dateirechte auf music.json prüfen.'
        );
        redirect_self();
    }

    redirect_self();
}

// ---- Render -----------------------------------------------------------------
$catalog = load_catalog(JSON_FILE);
$stations = $catalog['music'];
$writable = is_writable(JSON_FILE) || (!is_file(JSON_FILE) && is_writable(__DIR__));
$flash = $_SESSION['flash'] ?? null;
unset($_SESSION['flash']);

// Edit mode: ?edit=<id> pre-fills the form with that station's values.
$editId = isset($_GET['edit']) ? (string)$_GET['edit'] : '';
$editing = null;
foreach ($stations as $s) {
    if (($s['id'] ?? '') === $editId && $editId !== '') {
        $editing = $s;
        break;
    }
}

/** htmlspecialchars shortcut. */
function h($v): string
{
    return htmlspecialchars((string)$v, ENT_QUOTES, 'UTF-8');
}

/** Returns a form field's current value: the edited station's value, else a default. */
function fv(?array $editing, string $key, $default = '')
{
    return $editing[$key] ?? $default;
}
?>
<!doctype html>
<html lang="de">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>UAMP Sender-Verwaltung</title>
    <style>
        :root {
            --bg: #0f1115; --card: #191c22; --line: #2a2f39; --text: #e7e9ee;
            --muted: #9aa3b2; --accent: #4f8cff; --danger: #e5484d; --ok: #2ea043;
        }
        @media (prefers-color-scheme: light) {
            :root {
                --bg: #f4f6fa; --card: #ffffff; --line: #e2e6ee; --text: #1b1f27;
                --muted: #5b6472; --accent: #2563eb; --danger: #d21f28; --ok: #1a7f37;
            }
        }
        * { box-sizing: border-box; }
        body {
            margin: 0; padding: 24px; background: var(--bg); color: var(--text);
            font: 15px/1.5 system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
        }
        .wrap { max-width: 1000px; margin: 0 auto; }
        h1 { font-size: 22px; margin: 0 0 4px; }
        .sub { color: var(--muted); margin: 0 0 20px; font-size: 13px; }
        .card {
            background: var(--card); border: 1px solid var(--line); border-radius: 12px;
            padding: 20px; margin-bottom: 24px;
        }
        .flash { padding: 12px 14px; border-radius: 10px; margin-bottom: 20px; font-size: 14px; }
        .flash.ok { background: color-mix(in srgb, var(--ok) 18%, transparent); border: 1px solid var(--ok); }
        .flash.error { background: color-mix(in srgb, var(--danger) 18%, transparent); border: 1px solid var(--danger); }
        .warn { background: color-mix(in srgb, var(--danger) 12%, transparent); border: 1px solid var(--danger); }
        table { width: 100%; border-collapse: collapse; }
        th, td { text-align: left; padding: 10px 8px; border-bottom: 1px solid var(--line); vertical-align: middle; }
        th { color: var(--muted); font-size: 12px; text-transform: uppercase; letter-spacing: .04em; }
        td.small, th.small { font-size: 12px; color: var(--muted); }
        .thumb { width: 40px; height: 40px; border-radius: 8px; object-fit: cover; background: var(--line); }
        .st-title { font-weight: 600; }
        a { color: var(--accent); text-decoration: none; }
        a:hover { text-decoration: underline; }
        form.inline { display: inline; }
        .grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px 16px; }
        .grid .full { grid-column: 1 / -1; }
        label { display: block; font-size: 12px; color: var(--muted); margin-bottom: 4px; }
        input {
            width: 100%; padding: 9px 10px; border-radius: 8px; border: 1px solid var(--line);
            background: var(--bg); color: var(--text); font-size: 14px;
        }
        input:focus { outline: 2px solid var(--accent); border-color: transparent; }
        .row3 { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px 16px; }
        .btn {
            border: 0; border-radius: 8px; padding: 10px 16px; font-size: 14px; font-weight: 600;
            cursor: pointer; color: #fff; background: var(--accent);
        }
        .btn:hover { filter: brightness(1.08); }
        .btn-danger { background: transparent; color: var(--danger); border: 1px solid var(--danger); padding: 6px 12px; font-weight: 500; }
        .btn-danger:hover { background: var(--danger); color: #fff; }
        .btn-edit {
            display: inline-block; padding: 6px 12px; border-radius: 8px; font-size: 14px;
            border: 1px solid var(--accent); color: var(--accent); margin-right: 6px;
        }
        .btn-edit:hover { background: var(--accent); color: #fff; text-decoration: none; }
        td.nowrap { white-space: nowrap; }
        tr.editing { background: color-mix(in srgb, var(--accent) 12%, transparent); }
        .actions { margin-top: 16px; display: flex; align-items: center; gap: 14px; }
        .muted { color: var(--muted); font-size: 12px; }
        @media (max-width: 640px) {
            .grid, .row3 { grid-template-columns: 1fr; }
            td.hide-sm, th.hide-sm { display: none; }
        }
    </style>
</head>
<body>
<div class="wrap">
    <h1>UAMP Sender-Verwaltung</h1>
    <p class="sub">Bearbeitet <code><?= h(JSON_FILE) ?></code> — Änderungen erscheinen in der App
        beim nächsten Start oder Pull-to-Refresh.</p>

    <?php if ($flash): ?>
        <div class="flash <?= h($flash['type']) ?>"><?= h($flash['text']) ?></div>
    <?php endif; ?>

    <?php if (!$writable): ?>
        <div class="flash warn">⚠ <code>music.json</code> ist nicht beschreibbar. Rechte setzen, z. B.:
            <code>sudo chown www-data:www-data music.json &amp;&amp; sudo chmod 664 music.json</code></div>
    <?php endif; ?>

    <div class="card">
        <h2 style="margin:0 0 14px;font-size:16px;">Sender (<?= count($stations) ?>)</h2>
        <?php if (!$stations): ?>
            <p class="muted">Noch keine Sender.</p>
        <?php else: ?>
            <table>
                <thead>
                <tr>
                    <th class="small"></th>
                    <th>Titel</th>
                    <th class="hide-sm">Album</th>
                    <th class="hide-sm">Genre</th>
                    <th class="small hide-sm">ID</th>
                    <th>Stream</th>
                    <th></th>
                </tr>
                </thead>
                <tbody>
                <?php foreach ($stations as $s): ?>
                    <tr<?= ($editId !== '' && ($s['id'] ?? '') === $editId) ? ' class="editing"' : '' ?>>
                        <td>
                            <?php if (!empty($s['image'])): ?>
                                <img class="thumb" src="<?= h($s['image']) ?>" alt="" loading="lazy">
                            <?php else: ?>
                                <div class="thumb"></div>
                            <?php endif; ?>
                        </td>
                        <td class="st-title"><?= h($s['title'] ?? '') ?></td>
                        <td class="hide-sm"><?= h($s['album'] ?? '') ?></td>
                        <td class="hide-sm small"><?= h($s['genre'] ?? '') ?></td>
                        <td class="small hide-sm"><?= h($s['id'] ?? '') ?></td>
                        <td class="small">
                            <a href="<?= h($s['source'] ?? '') ?>" target="_blank" rel="noopener">öffnen ↗</a>
                        </td>
                        <td class="nowrap">
                            <a class="btn-edit" href="?edit=<?= h(rawurlencode($s['id'] ?? '')) ?>#form">Bearbeiten</a>
                            <form class="inline" method="post"
                                  onsubmit="return confirm('Sender „<?= h($s['title'] ?? '') ?>“ wirklich löschen?');">
                                <input type="hidden" name="action" value="delete">
                                <input type="hidden" name="id" value="<?= h($s['id'] ?? '') ?>">
                                <button class="btn-danger" type="submit">Löschen</button>
                            </form>
                        </td>
                    </tr>
                <?php endforeach; ?>
                </tbody>
            </table>
        <?php endif; ?>
    </div>

    <div class="card" id="form">
        <h2 style="margin:0 0 14px;font-size:16px;">
            <?= $editing ? 'Sender bearbeiten' : 'Sender hinzufügen' ?>
            <?php if ($editing): ?>
                <span class="muted">— <?= h($editing['title'] ?? '') ?></span>
            <?php endif; ?>
        </h2>
        <form method="post">
            <input type="hidden" name="action" value="<?= $editing ? 'update' : 'add' ?>">
            <?php if ($editing): ?>
                <input type="hidden" name="original_id" value="<?= h($editing['id'] ?? '') ?>">
            <?php endif; ?>
            <div class="grid">
                <div class="full">
                    <label>Titel * (wird in der App und in Android Auto angezeigt)</label>
                    <input name="title" required placeholder="z. B. WDR 2" value="<?= h(fv($editing, 'title')) ?>">
                </div>
                <div class="full">
                    <label>Stream-URL (source) *</label>
                    <input name="source" required type="url" placeholder="https://…/stream.mp3" value="<?= h(fv($editing, 'source')) ?>">
                </div>
                <div class="full">
                    <label>Bild-URL (image) — Sender-Logo / Artwork</label>
                    <input name="image" type="url" placeholder="https://…/logo.jpg" value="<?= h(fv($editing, 'image')) ?>">
                </div>
                <div>
                    <label>Album</label>
                    <input name="album" placeholder="z. B. WDR" value="<?= h(fv($editing, 'album')) ?>">
                </div>
                <div>
                    <label>Artist</label>
                    <input name="artist" placeholder="z. B. WDR" value="<?= h(fv($editing, 'artist')) ?>">
                </div>
                <div>
                    <label>Genre</label>
                    <input name="genre" placeholder="z. B. Pop" value="<?= h(fv($editing, 'genre')) ?>">
                </div>
                <div>
                    <label>Website (site)</label>
                    <input name="site" type="url" placeholder="https://…" value="<?= h(fv($editing, 'site')) ?>">
                </div>
                <div class="full">
                    <label>ID <?= $editing ? '(ändern ist möglich, muss eindeutig sein)' : '(optional — wird sonst aus dem Titel erzeugt, muss eindeutig sein)' ?></label>
                    <input name="id" placeholder="z. B. wdr2" value="<?= h(fv($editing, 'id')) ?>">
                </div>
            </div>
            <div class="row3" style="margin-top:12px;">
                <div>
                    <label>trackNumber</label>
                    <input name="trackNumber" type="number" value="<?= h(fv($editing, 'trackNumber', 1)) ?>">
                </div>
                <div>
                    <label>totalTrackCount</label>
                    <input name="totalTrackCount" type="number" value="<?= h(fv($editing, 'totalTrackCount', 1)) ?>">
                </div>
                <div>
                    <label>duration (Sek., −1 = Livestream)</label>
                    <input name="duration" type="number" value="<?= h(fv($editing, 'duration', -1)) ?>">
                </div>
            </div>
            <div class="actions">
                <button class="btn" type="submit"><?= $editing ? 'Speichern' : 'Hinzufügen' ?></button>
                <?php if ($editing): ?>
                    <a href="<?= h(strtok($_SERVER['REQUEST_URI'], '?')) ?>">Abbrechen</a>
                <?php endif; ?>
                <span class="muted">* Pflichtfeld</span>
            </div>
        </form>
    </div>
</div>
</body>
</html>
