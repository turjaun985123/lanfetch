# LanFetch — Release Checklist (desktop / web editions)

> Status: staged draft for V2 Wave 9. The repeatable procedure for
> shipping a `desktop-tauri-v*` release. Written from
> `.github/workflows/desktop-tauri.yml` (the release job is the
> authority — where this list and the workflow disagree, the
> workflow is what actually happens) and the RC6 precedent
> (2026-11-27), the first release whose assets were read back and
> verified end to end.

A release is **four assets, always the same four**:

| Asset | Built by | What it is |
|---|---|---|
| `lanfetch_<VERSION>_amd64.deb` | `bundle` job | Tauri desktop app; `Package: lanfetch` |
| `lanfetch-web-<VERSION>-linux-x86_64` | `bundle` job | Web edition, single binary, embedded UI |
| `lanfetch-web-<VERSION>-linux-arm64` | `web-arm64` job (native ARM runner) | Web edition for ARM machines |
| `lanfetch-web-<VERSION>-android-arm64` | `web-android-arm64` job (NDK, API 24) | Web edition to run under Termux on Android |

## 1. Preconditions (before tagging)

- [ ] `main` is at the intended commit and its Desktop Tauri CI run
      is **green at job and step level for that exact run id** —
      all of `frontend`, `backend`, `bundle`, `web-arm64`,
      `web-android-arm64` (release job skipped is expected on a
      branch push). A run-level "success" badge is not enough;
      check the jobs. (Android CI green too, if the wave touched
      shared code.)
- [ ] **Version consistency.** The frontend CI job hard-fails unless
      `package.json` == `src-tauri/Cargo.toml`. Per ADR-005 the
      same version must also be in `package-lock.json`,
      `engine/Cargo.toml`, and `web/Cargo.toml` — check all five;
      the .deb's version is derived from `src-tauri/Cargo.toml` by
      the packaging step, so a drift there ships a mislabeled
      package.
- [ ] `desktop-tauri/CHANGELOG.md` has the entry for this version
      (the release notes point readers at it).
- [ ] For a release candidate: the changes since the last RC are
      understood well enough to write honest notes (§4).
- [ ] For a **final** tag: §5's acceptance gate has happened. No
      exceptions.

## 2. Tag and publish

```sh
git tag desktop-tauri-v<VERSION>            # add -rc<N> for a candidate
git push origin desktop-tauri-v<VERSION>
```

- The tag push triggers the workflow on the tag ref. The `release`
  job runs only for `refs/tags/desktop-tauri-v*`, needs
  `bundle` + `web-arm64` + `web-android-arm64`, downloads the four
  artifacts, marks the three web binaries executable, and creates
  the GitHub release titled `LanFetch <tag>`.
- A tag containing `-rc` is automatically published as a
  **prerelease** (`--prerelease`); a plain version tag publishes
  as a full release. There is no manual asset upload step — if an
  asset is missing, a build job failed; fix the job, delete the
  tag/release if one was created, and re-tag. Never hand-upload a
  locally built binary as a release asset.

## 3. Read-back verification (after the release job goes green)

CI building an asset proves it compiled. It does not prove the
published file is the file that was built, intact, and runnable.
Download **every** asset from the published release page and check:

- [ ] **Asset set and sizes.** Exactly the four assets from the
      table. Record each size and compare against the release
      listing / the workflow artifacts. (RC6 precedent, for scale:
      .deb 3,919,688 B; linux-x86_64 5,408,424 B; linux-arm64
      4,920,816 B; android-arm64 4,824,432 B. Sizes drift between
      versions — a *mismatch with the listing* is the failure, not
      a difference from RC6.)
- [ ] **.deb identity.**
      `dpkg-deb -f <deb> Package` → exactly `lanfetch`;
      `dpkg-deb -f <deb> Version` → exactly the tag version.
      (CI asserts this too; the read-back proves the *published*
      file carries it. This identity is what keeps the dpkg
      upgrade path working across releases.)
- [ ] **ELF checks on the three web binaries** (`file` / `readelf`):
      linux-x86_64 → x86-64 ELF; linux-arm64 → AArch64 ELF;
      android-arm64 → AArch64 ELF **with interpreter
      `/system/bin/linker64`** (a Linux-interpreter Android binary
      would not run under Termux — this check is the whole point).
      ARM binaries cannot be executed on an x86 machine; their
      runtime verdict belongs to the user's devices (§5).
- [ ] **No-GUI-linkage sanity** for the web binaries was already
      asserted in CI (`ldd` must show no webkit/libgtk); the
      read-back `file` output should be consistent with a static,
      self-contained binary.
- [ ] **Live smoke of the x86_64 web binary.** This one *can* run
      here, so it must:
      ```sh
      chmod +x lanfetch-web-<VERSION>-linux-x86_64
      ./lanfetch-web-<VERSION>-linux-x86_64 --port 18787 &   # free port, not 8787
      curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18787/   # expect 200
      kill %1
      ```
      (RC6 precedent: the downloaded binary served HTTP 200 on its
      port.) Also confirm the process starts without a display /
      GUI libraries — it is the headless edition.
- [ ] **Release page sanity.** Title, prerelease flag (RCs only),
      and notes read correctly; the four download links resolve.

Only after every box is checked may the release be announced as
verified — and the announcement names what was checked.

## 4. Release-notes honesty rules

- **Never claim an updater.** LanFetch has no in-app updater and
  no auto-upgrade. The workflow's notes text states the truth:
  *"There is no in-app updater: upgrades are a manual install of
  the new .deb (or replacing the web binary), and your downloads,
  settings, and sources carry over."* Do not soften, shorten, or
  "improve" this into implying automatic updates — a previous
  release note falsely promised automatic upgrades and had to be
  corrected (V2 Wave 1).
- Notes describe what the release **does**, at the level that was
  verified: CI-tested behavior may be stated as working; anything
  verified only on the user's hardware is phrased as such until
  §5 happens. No throughput or performance claims (see
  PERFORMANCE.md — none are measured).
- Known limits ship in the notes, not in a follow-up comment:
  e.g. layout verification is a human checklist, not screenshot
  tests (RESPONSIVE-DESIGN.md §5); ARM binaries are
  build-verified, runtime-verified only on the user's devices.

## 5. Superseding the previous RC, and the final-tag gate

- When a new RC supersedes an older one, **annotate the old
  release**: edit its GitHub release notes to add a header naming
  it superseded by the new tag (the RC5 → RC6 precedent). Leave
  the old release downloadable; do not delete it — the annotation
  is the signal.
- **A final (non-RC) tag requires the user's own PC acceptance.**
  Concretely, the user has:
  - installed the RC `.deb` on their machine (in-place upgrade
    over the previous install, data intact),
  - retested the three behaviors that motivated the Tauri
    rebuild — category clicks don't latch, fetching works on
    later launches (not just the first), and speed is comparable
    to the Android app,
  - run the web edition on their phone,
  - passed `RESPONSIVE-QA.md` with **no open failure in rows
    7–11**.
  Until the user says so, ship RCs. An agent's CI-green verdict is
  not acceptance, and a final tag cut without it is a process
  failure even if the software is fine.
