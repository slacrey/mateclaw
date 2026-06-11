import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __filename = fileURLToPath(import.meta.url)
const desktopDir = path.resolve(path.dirname(__filename), '..')

for (const name of ['dist', 'release']) {
  fs.rmSync(path.join(desktopDir, name), { recursive: true, force: true })
}
