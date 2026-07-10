<p align="center">
  <img src="src/main/resources/assets/homovore/icon.png" alt="Homovore" width="180" />
</p>

<h1 align="center">Homovore</h1>

> **⚠️ NOTE: Please consider this the Homovore nightly/dev build.** 

### Join our discord: https://discord.gg/yMD5mhmNEc

<p align="center">
  <a href="https://github.com/leonetics/homovore-public/releases">
    <img src="https://img.shields.io/github/downloads/leonetics/homovore-public/total?color=green&label=Total%20Downloads" alt="Total Downloads" />
  </a>
  <a href="https://github.com/leonetics/homovore-public/commits">
    <img src="https://img.shields.io/github/commit-activity/m/leonetics/homovore-public?label=Commits%20(last%20month)&color=yellow" alt="month" />
  </a>
  <a href="https://github.com/leonetics/homovore-public/releases">
    <img src="https://img.shields.io/github/v/release/leonetics/homovore-public?color=blue&label=Latest%20Release" alt="Latest Release" />
  </a>
  <a href="https://discord.gg/yMD5mhmNEc">
    <img src="https://img.shields.io/discord/1481831852950950043?color=7289DB&label=Discord" alt="Discord" />
  </a>
</p>

**Homovore** is a 2b2t PVP client, written by Leonetic and WiderThanEurasia.

This project is a clean and open-source yet modern alternative aiming to be transparent and easy to extend without relying on unsafe third-party clients.

---

## Screenshots

<details>
<summary>View screenshots</summary>

<img width="3440" height="1440" alt="ClickGUI" src="screenshots/Screenshot%202026-06-22%20at%206.57.01%E2%80%AFPM.png" />
<img width="3440" height="1440" alt="ClickGUI module settings" src="screenshots/Screenshot%202026-06-22%20at%206.57.27%E2%80%AFPM.png" />
<img width="3440" height="1440" alt="In-game PVP" src="screenshots/Screenshot%202026-06-21%20at%207.55.50%E2%80%AFPM.png" />

</details>

---

## FAQ

<details>
<summary>How do I open the ClickGUI?</summary>

The default keybind is **Right Shift**. You can rebind it in Minecraft's Controls menu (look for the Homovore category) or from the ClickGUI module itself.

</details>

<details>
<summary>What is the command prefix?</summary>

The default command prefix is `.`. For example, `.help` or `.bind`. You can change it with the `.prefix <new>` command.

</details>

<details>
<summary>How are modules organized?</summary>

Modules are grouped by category. Open the ClickGUI to browse and toggle them, or bind individual modules to a key.

</details>

<details>
<summary>How do I bind a module to a key?</summary>

Open the ClickGUI, right-click a module to expand its settings, and use the bind option, or use the `.bind` command.

</details>

<details>
<summary>Where are my settings saved?</summary>

Configs are stored in your Minecraft instance directory under the Homovore folder and persist between sessions.

</details>

---

## Requirements

- Java 21
- Gradle 8+
- Minecraft 1.21.11
- Fabric loader, API

---

## How to Build

1. Clone the repository:

    ```bash
    git clone https://github.com/leonetics/homovore-public.git
    cd homovore-public
    ```

2. Build with Gradle:

    ```bash
    ./gradlew build
    ```

The compiled JAR will be located at:
`build/libs/homovore-<version>.jar`

To run in a dev environment:

```bash
./gradlew runClient
```

---

## Project layout

- `src/main/java/dev/leonetic/` — mod source
  - `features/modules/` — modules grouped by category (combat, movement, render, …)
  - `manager/` — core managers (modules, rotations, placement, swapping, …)
  - `event/` — the event bus and event definitions
  - `mixin/` — mixins into Minecraft classes
- `src/main/resources/` — `fabric.mod.json`, mixin configs, access widener, shaders, assets

---

## License

See [LICENSE](LICENSE).

---

## Special Thanks

- [kiriyaga](https://github.com/Kiriyaga7615) — **Homovore would not have been possible without him.** Endless thanks for the foundation, guidance, and inspiration that made this client what it is.

- [Gonbler](https://github.com/gonbler) — some of Homovore's PVP stuff is built on his code. Thanks for the work on GWare that made those modules possible.

- [Pawstar](https://github.com/pawztar) — moral support
