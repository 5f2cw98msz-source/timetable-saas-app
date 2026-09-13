# Running Chalkline on a Windows laptop

Start here. This gets Chalkline running on your own machine so you can click
around it, break it, and change it. Nothing here costs money and nothing
touches the internet except one download.

Roughly 15 minutes, most of which is waiting for downloads.

You do **not** need Maven, Docker, or a database. You need Java, and that is it.

---

## Step 1: Install Java 21

Chalkline needs **JDK 21**. Not 17, not 24. If you already have a different
version for another project, do not uninstall it. Step 4 shows how to point at
the right one without breaking anything else.

1. Go to <https://adoptium.net/temurin/releases/?version=21>
2. Choose:
   - **Operating System:** Windows
   - **Architecture:** x64
   - **Package Type:** JDK
   - **Version:** 21
3. Download the **.msi** installer and run it.
4. On the **Custom Setup** screen, click the dropdown next to
   **Set JAVA_HOME variable** and choose **Will be installed on local hard
   drive**.

   This one dropdown saves you a lot of pain later. If you miss it, see
   *JAVA_HOME* in Troubleshooting below.

5. Finish the installer.

### Check it worked

Open a **new** PowerShell window. (It must be new. An open window does not
pick up variables set after it opened.)

Press <kbd>Win</kbd>, type `powershell`, press Enter. Then:

```powershell
java -version
```

You want to see something starting with `openjdk version "21`:

```
openjdk version "21.0.5" 2024-10-15 LTS
OpenJDK Runtime Environment Temurin-21.0.5+11 (build 21.0.5+11-LTS)
```

If it says a different number, or `'java' is not recognized`, go to
Troubleshooting.

---

## Step 2: Install Git

1. Go to <https://git-scm.com/download/win> and run the installer.
2. Accept every default. There are a lot of screens; just keep clicking Next.

Check it in a new PowerShell window:

```powershell
git --version
```

> **Already have GitHub Desktop or use IntelliJ's built-in Git?** That works
> too. Skip to Step 3 and clone however you normally do.

---

## Step 3: Download the code

In PowerShell:

```powershell
cd $HOME\Documents
git clone https://github.com/5f2cw98msz-source/timetable-saas-app.git chalkline
cd chalkline
```

You now have the project in `C:\Users\YourName\Documents\chalkline`.

---

## Step 4: Run it

```powershell
.\mvnw.cmd spring-boot:run
```

That `.\` at the front is not optional on Windows. PowerShell will not run a
script in the current folder without it.

**The first run takes 2 to 5 minutes** while it downloads Maven and every
library. It will look like it has frozen. It has not. Later runs take about
10 seconds.

You are ready when you see:

```
Started TimetableApplication in 3.4 seconds
```

Leave that window open. **Closing it stops the app.**

> **Windows Firewall may pop up** asking whether to allow Java to accept
> connections. Click **Cancel** or **Allow access** on private networks only.
> You are connecting from the same machine, so it works either way.

---

## Step 5: Open it

Open your browser and go to:

```
http://localhost:8080
```

You should see the Chalkline home page.

Click **Start free** and fill in the form. Use anything you like:

| | |
|---|---|
| Institution | Abetifi Technical University |
| Your name | your name |
| Work email | anything, e.g. `you@test.edu` |
| Password | at least 8 characters |

There is no email confirmation. The account works immediately, and it is the
administrator for your institution.

Sign in, and you have an empty timetable with a few sample rooms and courses
already in it. Click any empty slot to add a class.

---

## Step 6: Turn on the Premium features

The paid features are switched off by default. To see them without setting up
payments, stop the app (<kbd>Ctrl</kbd>+<kbd>C</kbd> in the PowerShell window)
and start it like this instead:

```powershell
$env:ALLOW_MANUAL_UPGRADE="true"
.\mvnw.cmd spring-boot:run
```

Then go to **Settings &rarr; Plan & billing** and click
**Try Premium without paying (demo)**.

That unlocks public student pages, calendar subscriptions, the API, webhooks,
bulk import, export and the audit log. It only works because no Stripe keys
are configured; the moment real payments are set up, that button refuses to
work.

> **PowerShell, not Command Prompt.** The `$env:NAME="value"` syntax is
> PowerShell. In old Command Prompt (`cmd.exe`) it is `set NAME=value`.
> If you copied a command from a Mac or Linux guide that looks like
> `ALLOW_MANUAL_UPGRADE=true ./mvnw spring-boot:run`, that will **not** work
> on Windows. Set the variable on its own line first.

---

## Everyday commands

Run all of these from the `chalkline` folder in PowerShell.

| What you want | Command |
|---|---|
| Start it | `.\mvnw.cmd spring-boot:run` |
| Stop it | <kbd>Ctrl</kbd>+<kbd>C</kbd> in that window |
| Run the tests | `.\mvnw.cmd test` |
| Build a single runnable file | `.\mvnw.cmd package` |
| Run that file | `java -jar target\chalkline.jar` |
| Get the latest code | `git pull` |

### Starting over with an empty database

Your data lives in a `data` folder inside the project. Delete it and you get a
completely fresh start:

```powershell
.\mvnw.cmd spring-boot:run    # stop it first with Ctrl+C
Remove-Item -Recurse -Force .\data
```

There is no undo. That deletes every account and timetable you created locally.

---

## Running it in IntelliJ instead

You built the original in IntelliJ, so this will feel familiar.

1. **File &rarr; Open**, choose the `chalkline` folder, and click OK.
2. IntelliJ sees `pom.xml` and offers to load it as a Maven project. Say yes,
   and wait for the progress bar at the bottom to finish.
3. **File &rarr; Project Structure &rarr; Project** and set **SDK** to your
   Java 21 installation. If it is not listed, click **Add SDK &rarr; JDK** and
   point it at `C:\Program Files\Eclipse Adoptium\jdk-21...`
4. Open `src/main/java/com/chalkline/TimetableApplication.java`
5. Click the green &#9654; arrow next to `public class TimetableApplication`
   and choose **Run 'TimetableApplication'**.

To set an environment variable in IntelliJ: **Run &rarr; Edit Configurations**,
select `TimetableApplication`, and add it to the **Environment variables**
field, e.g. `ALLOW_MANUAL_UPGRADE=true`.

---

## Troubleshooting

### `'java' is not recognized as the name of a cmdlet`

Java is not installed, or not on your PATH. Reinstall from Step 1 and make sure
you set **JAVA_HOME** during the install. Then open a **new** PowerShell window.

### `java -version` shows the wrong version

You have more than one Java installed and Windows is finding the wrong one
first. Point this project at the right one without changing anything else:

```powershell
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot"
.\mvnw.cmd spring-boot:run
```

Check the exact folder name first:

```powershell
ls "C:\Program Files\Eclipse Adoptium"
```

That lasts for the current PowerShell window only, which is exactly what you
want when other projects need a different Java.

### `The JAVA_HOME environment variable is not defined correctly`

Same fix as above. Note it must point at the JDK **folder**, not at
`...\bin\java.exe`.

### `Web server failed to start. Port 8080 was already in use.`

Something else has that port. Either use a different one:

```powershell
$env:PORT="8090"
.\mvnw.cmd spring-boot:run
```

then open `http://localhost:8090`.

Or find what is using 8080 and stop it:

```powershell
netstat -ano | findstr :8080
```

The last number on the line is the process id. Look it up before killing it:

```powershell
Get-Process -Id <the-number>
```

If it is an old Chalkline you forgot to stop, `Stop-Process -Id <the-number>`.

### `.\mvnw.cmd : File cannot be loaded because running scripts is disabled`

Windows is blocking scripts. Allow them for your own account:

```powershell
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned
```

Answer `Y`. This affects your user account only, not the whole machine.

### The first run seems stuck

It is downloading Maven and around 80 MB of libraries. Give it 5 minutes on a
slow connection. If nothing appears at all after that, stop it with
<kbd>Ctrl</kbd>+<kbd>C</kbd> and run `.\mvnw.cmd -X spring-boot:run` to see
what it is doing.

### It starts, but the page will not load

Check the PowerShell window for `Started TimetableApplication`. If you see a
stack trace instead, the error is usually in the first few lines after
`Caused by:`.

Make sure you typed `http://localhost:8080` and not `https://`. There is no
certificate locally, so `https` will not work.

### Antivirus is slowing everything down

Some antivirus scans every file Maven writes, which makes the first build very
slow. If it is unbearable, add your `chalkline` folder to your antivirus
exclusions. Do not disable the antivirus.

---

## What next

- Read [README.md](README.md) for what the app does and how the code is
  organised.
- When you want it online so other people can use it, read
  [DEPLOYMENT.md](DEPLOYMENT.md). If your university will give you a server,
  [DEPLOY-SELF-HOSTED.md](DEPLOY-SELF-HOSTED.md) is the cheapest and simplest
  route, and the one to start with.
- The whole of `src/main/java/com/chalkline/` is yours to change. Start with
  `service/TimetableService.java`, which is where the rules about who can edit
  what actually live.
