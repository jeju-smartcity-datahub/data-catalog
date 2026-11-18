/** Web process liveness only. No catalog API call, database read or search event. */
export const dynamic = 'force-dynamic';

export function GET() {
  return Response.json({ status: 'ok', component: 'web' }, {
    headers: { 'Cache-Control': 'no-store' },
  });
}
