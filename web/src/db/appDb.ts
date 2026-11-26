/** The app's local database: open, migrate, persist, query. */
import type { Database, SqlValue } from 'sql.js'
import { SCHEMA_SQL, SCHEMA_VERSION } from './schema'
import { getSql } from './sqljs'
import { deleteDbFile, loadDbFile, saveDbFile } from './storage'

let dbPromise: Promise<Database> | null = null

export function migrate(db: Database) {
  db.exec(SCHEMA_SQL)
  db.run("INSERT OR REPLACE INTO meta (key, value) VALUES ('schema_version', ?)", [String(SCHEMA_VERSION)])
}

export function openAppDb(): Promise<Database> {
  if (!dbPromise) {
    dbPromise = (async () => {
      const SQL = await getSql()
      const bytes = await loadDbFile()
      const db = bytes ? new SQL.Database(bytes) : new SQL.Database()
      migrate(db)
      return db
    })()
  }
  return dbPromise
}

export async function persist() {
  const db = await openAppDb()
  await saveDbFile(db.export())
}

export async function resetAppDb() {
  await deleteDbFile()
  dbPromise = null
}

export async function exportDbBytes(): Promise<Uint8Array> {
  return (await openAppDb()).export()
}

export function query<T = Record<string, SqlValue>>(db: Database, sql: string, params: SqlValue[] = []): T[] {
  const st = db.prepare(sql)
  st.bind(params)
  const out: T[] = []
  while (st.step()) out.push(st.getAsObject() as T)
  st.free()
  return out
}
