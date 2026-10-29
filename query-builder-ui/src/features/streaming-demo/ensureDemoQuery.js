import { AUTHOR } from '../../config.js'

/**
 * Makes sure the demo query is published, so the page works on a fresh database in one click.
 * If it is already published it is used as is; otherwise it is drafted, simulated and published
 * through the same endpoints the Query Builder uses. Returns 'existing' or 'created'.
 */
export async function ensureDemoQuery(client, demo, signal) {
  const { items = [] } = await client.list({}, { signal })
  if (items.find((i) => i.queryName === demo.queryName)?.published) return 'existing'

  const entry = { queryName: demo.queryName, sql: demo.sql, params: demo.params, fetchSize: demo.fetchSize }
  const draft = await client.draft(entry, AUTHOR, { signal })
  if (draft.errors?.length) {
    throw new Error(`The demo query did not validate: ${draft.errors.map((e) => `${e.code} ${e.message}`).join('; ')}`)
  }
  await client.simulate(demo.queryName, demo.args, { signal })
  await client.publish(demo.queryName, AUTHOR, [demo.role], { signal })
  return 'created'
}
