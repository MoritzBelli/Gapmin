import initSqlJs, { type SqlJsStatic } from 'sql.js'

let sqlPromise: Promise<SqlJsStatic> | null = null

/** Load sql.js once. In the browser the wasm file is served by Vite; in Node it is resolved locally. */
export function getSql(): Promise<SqlJsStatic> {
  if (!sqlPromise) {
    sqlPromise = (async () => {
      if (typeof window === 'undefined') return initSqlJs()
      const { default: wasmUrl } = await import('sql.js/dist/sql-wasm.wasm?url')
      return initSqlJs({ locateFile: () => wasmUrl })
    })()
  }
  return sqlPromise
}
