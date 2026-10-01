# Arif Build AI Native V4.4

V4.4 focuses on turning Arif Build AI from a chat client into an actual AI build workspace.

## New in V4.4
- AI Build Engine: user instruction is sent to OpenRouter with a strict JSON build protocol.
- AI can create/update multiple project files and request safe file deletion.
- Build Log records file changes and errors.
- Live Preview renders the current local `index.html` and its relative CSS/JS assets from the project workspace.
- Project import/export ZIP remains available.
- OpenRouter model loading and manual model selection remain available.
- Cloud deploy workflow remains available through a backend API.
- Hosting credentials are not embedded in the APK.

## Build protocol
The AI must return JSON with `message`, `files`, and `delete`. The client validates project paths before applying changes.

## GitHub Actions
Push this project to GitHub and run **Build Arif Build AI V4.4**. The workflow builds a debug APK and uploads it as an artifact.

## Important
This repository is source code. The APK is not claimed to have been locally compiled in this environment.
