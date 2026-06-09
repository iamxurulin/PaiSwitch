import {
  cpSync,
  existsSync,
  lstatSync,
  mkdirSync,
  readdirSync,
  readlinkSync,
  rmSync,
  statSync,
  unlinkSync
} from 'node:fs'
import { dirname, isAbsolute, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { execFileSync } from 'node:child_process'

const __dirname = dirname(fileURLToPath(import.meta.url))
const webRoot = resolve(__dirname, '..')
const repoRoot = resolve(webRoot, '..')
const backendRoot = resolve(repoRoot, 'paiswitch-backend')
const backendJar = resolve(backendRoot, 'target/paiswitch-backend-1.0.0-SNAPSHOT.jar')
const resourcesRoot = resolve(webRoot, 'src-tauri/resources')
const bundledBackendDir = resolve(resourcesRoot, 'backend')
const bundledBackendJar = resolve(bundledBackendDir, 'paiswitch-backend.jar')
const bundledRuntimeDir = resolve(resourcesRoot, 'runtime')

const runtimeModules = [
  'java.base',
  'java.compiler',
  'java.datatransfer',
  'java.desktop',
  'java.instrument',
  'java.logging',
  'java.management',
  'java.naming',
  'java.net.http',
  'java.prefs',
  'java.rmi',
  'java.scripting',
  'java.security.jgss',
  'java.security.sasl',
  'java.sql',
  'java.transaction.xa',
  'java.xml',
  'java.xml.crypto',
  'jdk.charsets',
  'jdk.crypto.ec',
  'jdk.unsupported',
  'jdk.zipfs'
]

function run(command, args, options = {}) {
  execFileSync(command, args, {
    cwd: repoRoot,
    stdio: 'inherit',
    ...options
  })
}

function resolveJavaHome() {
  if (process.env.JAVA_HOME) {
    return process.env.JAVA_HOME
  }

  if (process.platform === 'darwin') {
    return execFileSync('/usr/libexec/java_home', { encoding: 'utf8' }).trim()
  }

  throw new Error('JAVA_HOME is required to build the bundled desktop runtime')
}

function buildBackendJar() {
  run('mvn', ['-q', '-DskipTests', 'package'], { cwd: backendRoot })
  mkdirSync(bundledBackendDir, { recursive: true })
  cpSync(backendJar, bundledBackendJar)
}

function buildRuntime() {
  const javaHome = resolveJavaHome()
  const jlink = resolve(javaHome, 'bin/jlink')

  if (!existsSync(jlink)) {
    throw new Error(`jlink not found at ${jlink}`)
  }

  rmSync(bundledRuntimeDir, { recursive: true, force: true })
  mkdirSync(resourcesRoot, { recursive: true })

  run(jlink, [
    '--add-modules',
    runtimeModules.join(','),
    '--no-header-files',
    '--no-man-pages',
    '--strip-debug',
    '--compress=zip-6',
    '--output',
    bundledRuntimeDir
  ])

  materializeSymlinks(bundledRuntimeDir)
  run('chmod', ['-R', 'u+rwX', bundledRuntimeDir])
  rmSync(resolve(bundledRuntimeDir, 'legal'), { recursive: true, force: true })
}

function materializeSymlinks(root) {
  for (const entry of readdirSync(root)) {
    const entryPath = join(root, entry)
    const stat = lstatSync(entryPath)

    if (stat.isSymbolicLink()) {
      const linkTarget = readlinkSync(entryPath)
      const targetPath = isAbsolute(linkTarget)
        ? linkTarget
        : resolve(dirname(entryPath), linkTarget)
      const targetStat = statSync(targetPath)

      unlinkSync(entryPath)
      cpSync(targetPath, entryPath, {
        recursive: targetStat.isDirectory(),
        dereference: true
      })
      continue
    }

    if (stat.isDirectory()) {
      materializeSymlinks(entryPath)
    }
  }
}

buildBackendJar()
buildRuntime()
