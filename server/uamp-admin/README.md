# UAMP Sender-Verwaltung (Web)

Kleine PHP-Seite zum **Hinzufügen und Löschen** von Musikquellen (Radiosendern) in der
`music.json`, die die Android-App lädt. Sie bearbeitet die `music.json` **im selben
Verzeichnis** (`__DIR__/music.json`).

## Funktionen
- Liste aller Sender mit Logo-Vorschau und Stream-Link
- Sender hinzufügen (Titel + Stream-URL Pflicht; ID wird sonst aus dem Titel erzeugt)
- Sender löschen (mit Bestätigung)
- Atomares Schreiben (`.tmp` → `rename`) und automatisches Backup in `music.json.bak`
- JSON-Schema identisch zu `JsonMusic` der App: `id, title, album, artist, genre, source,
  image, trackNumber, totalTrackCount, duration, site`

Änderungen erscheinen in der App beim nächsten Start, beim stündlichen Auto-Refresh oder
sofort per **Pull-to-Refresh**.

## Deployment (Server 192.168.120.126, `/var/www/html/uamp/`)

1. Datei kopieren (neben die vorhandene `music.json`):
   ```bash
   scp server/uamp-admin/index.php <user>@192.168.120.126:/var/www/html/uamp/index.php
   ```
2. PHP muss auf dem Server aktiv sein (Apache: `sudo apt install php libapache2-mod-php &&
   sudo systemctl reload apache2`).
3. Schreibrechte für den Webserver-Nutzer auf die JSON setzen:
   ```bash
   sudo chown www-data:www-data /var/www/html/uamp/music.json
   sudo chmod 664 /var/www/html/uamp/music.json
   # Backup/atomares Schreiben brauchen zusätzlich Schreibrecht auf das Verzeichnis:
   sudo chown www-data:www-data /var/www/html/uamp
   ```
4. Öffnen: `http://192.168.120.126/uamp/` (bzw. `.../uamp/index.php`).

## Zugriff aufs lokale Netz beschränken
Die Seite hat **keine Authentifizierung**. Nur die LANs `192.168.120.0/24` und `192.168.122.0/24`
(plus localhost) dürfen `index.php` öffnen; `music.json` und die Bilder bleiben öffentlich, weil
die App sie lädt.

**Aktuell auf dem Server (192.168.120.126) deployt so** — als globaler `<Directory>`-Block, weil
Apache dort `AllowOverride None` hat (eine `.htaccess` würde ignoriert):

```bash
scp server/uamp-admin/uamp-admin-restrict.conf \
    root@192.168.120.126:/etc/apache2/conf-available/uamp-admin-restrict.conf
ssh root@192.168.120.126 'a2enconf uamp-admin-restrict && apache2ctl configtest && systemctl reload apache2'
```

Inhalt (`uamp-admin-restrict.conf`):
```apache
<Directory "/var/www/html/uamp">
    <Files "index.php">
        Require ip 192.168.120.0/24
        Require ip 192.168.122.0/24
        Require ip 127.0.0.1 ::1
    </Files>
</Directory>
```

**Alternative** (`.htaccess` liegt ebenfalls bei) nur falls `AllowOverride AuthConfig`/`All` für das
Verzeichnis aktiv ist — dann `server/uamp-admin/.htaccess` nach `/var/www/html/uamp/.htaccess`
kopieren. Auf diesem Server ist das nicht der Fall, daher wird der Conf-Block oben verwendet.

### Verifizieren
- Von einem Rechner **im** LAN: `http://192.168.120.126/uamp/index.php` → erreichbar.
- Von **außerhalb** (öffentlich) muss es **403** liefern, `music.json` weiterhin **200**:
  ```bash
  curl -s -o /dev/null -w "index.php: %{http_code}\n" https://app.kenfenheuer.net/uamp/index.php
  curl -s -o /dev/null -w "music.json: %{http_code}\n" https://app.kenfenheuer.net/uamp/music.json
  ```

### Alternative: HTTP-Basic-Auth
Falls stattdessen ein Passwort gewünscht ist:
```apache
<Files "index.php">
    AuthType Basic
    AuthName "UAMP Admin"
    AuthUserFile /var/www/html/uamp/.htpasswd
    Require valid-user
</Files>
```
(`sudo htpasswd -c /var/www/html/uamp/.htpasswd admin`)
