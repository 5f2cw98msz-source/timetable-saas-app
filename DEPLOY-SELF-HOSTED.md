# Running it on your own server

For a university server, or any Linux machine you already have.

**This is the cheapest and simplest option, and for one institution it is
usually the right one.** No cloud account, no per-month bill, and the data never
leaves your own network. Start here, and move to a cloud later only if you
actually need to.

You need a Linux machine with Docker, and somewhere for people to reach it.

---

## 1. Install Docker

On Ubuntu or Debian:

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER
```

Sign out and back in, then check:

```bash
docker --version
docker compose version
```

---

## 2. Get the code

```bash
git clone https://github.com/5f2cw98msz-source/timetable-saas-app.git chalkline
cd chalkline
```

---

## 3. Configure it

`docker-compose.yml` ships with development defaults. Change them before it goes
anywhere real.

```bash
cp .env.example .env
nano .env
```

The ones that matter:

```
DATABASE_PASSWORD=<a long random password>
PUBLIC_URL=https://timetable.your-university.edu
SUPPORT_EMAIL=timetable@your-university.edu
ALLOW_SIGN_UP=false
SESSION_COOKIE_SECURE=true
DEMO_ORG_EMAIL=
```

Then point `docker-compose.yml` at the file by replacing the inline
`environment:` block on the `app` service with:

```yaml
    env_file: .env
```

and set the database password in both places, or use the same variable:

```yaml
  db:
    environment:
      POSTGRES_DB: chalkline
      POSTGRES_USER: chalkline
      POSTGRES_PASSWORD: ${DATABASE_PASSWORD}
```

Generate a good password with:

```bash
openssl rand -base64 24
```

---

## 4. Start it

```bash
docker compose up -d --build
docker compose logs -f app
```

Wait for `Started TimetableApplication`, then:

```bash
curl localhost:8080/actuator/health     # {"status":"UP"}
```

It restarts automatically with the machine, because both services are marked
`restart: unless-stopped`.

---

## 5. Put HTTPS in front of it

The app listens on plain HTTP on port 8080. Do not expose that directly:
`SESSION_COOKIE_SECURE=true` requires HTTPS, and without it nobody can sign in.

### Option A: Caddy, which gets certificates on its own

```bash
sudo apt install -y caddy
sudo nano /etc/caddy/Caddyfile
```

```
timetable.your-university.edu {
    reverse_proxy localhost:8080
}
```

```bash
sudo systemctl reload caddy
```

That is the whole configuration. Caddy obtains and renews the certificate
automatically, as long as the domain points at this machine and ports 80 and 443
are open.

### Option B: Nginx with an existing university certificate

Most universities issue their own certificates, in which case use theirs:

```nginx
server {
    listen 443 ssl;
    server_name timetable.your-university.edu;

    ssl_certificate     /etc/ssl/certs/your-university.crt;
    ssl_certificate_key /etc/ssl/private/your-university.key;

    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;

        # Without this the app thinks it is on http:// and builds share
        # links and redirects with the wrong scheme.
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}

server {
    listen 80;
    server_name timetable.your-university.edu;
    return 301 https://$host$request_uri;
}
```

```bash
sudo nginx -t && sudo systemctl reload nginx
```

The `X-Forwarded-Proto` header is the one people forget. The app reads it
(`server.forward-headers-strategy: framework`) to work out its real public
address.

---

## 6. Back the database up

Nothing else in this guide matters as much as this.

```bash
sudo mkdir -p /var/backups/chalkline
sudo nano /usr/local/bin/chalkline-backup.sh
```

```bash
#!/bin/bash
set -euo pipefail
BACKUP_DIR=/var/backups/chalkline
STAMP=$(date +%Y%m%d-%H%M%S)

cd /home/YOUR_USER/chalkline
docker compose exec -T db pg_dump -U chalkline chalkline \
  | gzip > "$BACKUP_DIR/chalkline-$STAMP.sql.gz"

# Keep 30 days.
find "$BACKUP_DIR" -name 'chalkline-*.sql.gz' -mtime +30 -delete
```

```bash
sudo chmod +x /usr/local/bin/chalkline-backup.sh
sudo crontab -e
```

```
0 2 * * * /usr/local/bin/chalkline-backup.sh
```

**Then test a restore, today, before there is anything to lose:**

```bash
gunzip -c /var/backups/chalkline/chalkline-YYYYMMDD-HHMMSS.sql.gz \
  | docker compose exec -T db psql -U chalkline chalkline
```

An untested backup is not a backup. Copy them off this machine as well: a
backup on the same disk does not survive the disk.

---

## 7. Create your institution

Open the site, click **Start free**, and create it. Then set `ALLOW_SIGN_UP=false`
and restart, so nobody else can:

```bash
docker compose up -d
```

---

## Updating

```bash
cd ~/chalkline
/usr/local/bin/chalkline-backup.sh     # back up first
git pull
docker compose up -d --build
docker compose logs -f app
```

---

## Useful commands

```bash
docker compose ps                       # what is running
docker compose logs -f app              # follow the application log
docker compose restart app              # restart just the app
docker compose down                     # stop, keeping data
docker compose down -v                  # stop and DELETE ALL DATA
docker compose exec db psql -U chalkline chalkline    # a database shell
```

---

## If the university will not give you a server

A small VPS is a few pounds a month and will run this comfortably: one vCPU and
2 GB of memory is plenty for a department. The instructions above work unchanged
on any Ubuntu machine.
