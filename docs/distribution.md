# Run the Settlement Lab distribution

The `settlement-lab-0.1.0.zip` archive contains the Java application, runtime dependencies, database migrations, Linux and Windows launchers, and the Compose file for a local PostgreSQL 18 lab. Install Java 21 and extract the whole archive into a writable directory.

## Run the in-memory fixture

Open a terminal in the extracted `settlement-lab-0.1.0` directory. The fixture runs offline with a Java 21 runtime. If several Java installations are present, set `JAVA_HOME` to the Java 21 installation directory.

On Linux, use the shell launcher.

```sh
./bin/settlement-lab demo cycle
```

In Windows PowerShell, use the batch launcher.

```powershell
.\bin\settlement-lab.bat demo cycle
```

Three obligations of 100 settle together. Look for `gross settled=300`, closing balances of 20 each and a shared batch ID for payments AB, BC and CA. Pass `--race cancel-first` after `demo cycle` to see the order in which cancellation prevents the settlement.

## Run the durable race

Start Docker with the Compose plugin. From the same extracted directory, run `docker compose up -d --wait`. The first start downloads the PostgreSQL image. The default database binds to `127.0.0.1:55432`.

```sh
./bin/settlement-lab pg race settle-first --drop-reply
```

On Windows use `.\bin\settlement-lab.bat pg race settle-first --drop-reply`. Each invocation creates a fresh synthetic domain. The CLI waits for PostgreSQL to observe the competing writer's lock wait, then recovers the winning receipt on a new connection. Look for `wait_event_type=Lock` and `reply_recovered=true`. Run `docker compose down` to stop the lab and retain its data volume.

## Obtaining the ZIP

Download the `settlement-lab-0.1.0` artifact of a successful **Package distribution** workflow for the desired commit. The GitHub artifact contains the application ZIP. Extract the GitHub download first and then the application ZIP.

To build the distribution from a checkout with a JDK 21 installation, run `./gradlew distZip` on Linux or `.\gradlew.bat distZip` in PowerShell. The application ZIP is written to `build/distributions/`.
