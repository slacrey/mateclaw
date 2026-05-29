// One-shot: generate a stable RSA keypair for the unpacked extension, derive
// the deterministic Chrome extension ID, and emit the public `key` (for
// manifest.json) + the ID (for externally_connectable + admin UI). Run once;
// the private key is written to .dev-extension-key.pem (gitignored) so the ID
// stays stable across rebuilds. Re-running regenerates a DIFFERENT id — only
// run again if you intend to rotate.
//
//   node scripts/gen-extension-key.cjs
//
const crypto = require('crypto')
const fs = require('fs')
const path = require('path')

const keyFile = path.join(__dirname, '..', '.dev-extension-key.pem')

let privatePem
if (fs.existsSync(keyFile)) {
  privatePem = fs.readFileSync(keyFile, 'utf8')
  console.error('[gen-key] reusing existing .dev-extension-key.pem')
} else {
  const { privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 })
  privatePem = privateKey.export({ type: 'pkcs8', format: 'pem' })
  fs.writeFileSync(keyFile, privatePem, { mode: 0o600 })
  console.error('[gen-key] wrote new .dev-extension-key.pem')
}

const privateKey = crypto.createPrivateKey(privatePem)
const publicKey = crypto.createPublicKey(privateKey)
const der = publicKey.export({ type: 'spki', format: 'der' })

const keyB64 = der.toString('base64')
const hashHex = crypto.createHash('sha256').update(der).digest('hex').slice(0, 32)
const id = [...hashHex].map((c) => 'abcdefghijklmnop'[parseInt(c, 16)]).join('')

console.log('KEY=' + keyB64)
console.log('ID=' + id)
