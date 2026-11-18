export type DcatFormat = 'jsonld' | 'turtle';
export type DcatFeed = { body: string; total: number; page: number; format: DcatFormat };

export const DCAT_PAGE_SIZE = 100;
const MAX_FEED_BYTES = 8 * 1024 * 1024;

export function dcatMediaType(format: DcatFormat): string {
  return format === 'turtle' ? 'text/turtle' : 'application/ld+json';
}

export function dcatPath(page: number, format?: DcatFormat): string {
  if (!Number.isSafeInteger(page) || page < 0) throw new Error('조회 페이지가 올바르지 않습니다.');
  return `/api/catalog/v1/dcat${format ? '/download' : ''}?page=${page}&size=${DCAT_PAGE_SIZE}${format ? `&format=${format}` : ''}`;
}

async function boundedText(response: Response): Promise<string> {
  const length = response.headers.get('Content-Length');
  if (length !== null && Number(length) > MAX_FEED_BYTES) throw new Error('응답 크기가 조회 범위를 초과했습니다.');
  if (!response.body) throw new Error('응답 본문이 없습니다.');
  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8', { fatal: true });
  let size = 0;
  let text = '';
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_FEED_BYTES) {
        await reader.cancel();
        throw new Error('응답 크기가 조회 범위를 초과했습니다.');
      }
      text += decoder.decode(value, { stream: true });
    }
    return text + decoder.decode();
  } finally { reader.releaseLock(); }
}

export async function readDcatFeed(format: DcatFormat, page: number, signal: AbortSignal, download = false, request: typeof fetch = fetch): Promise<DcatFeed> {
  const response = await request(dcatPath(page, download ? format : undefined), {
    headers: { Accept: dcatMediaType(format) }, credentials: 'same-origin', cache: 'no-store', signal,
  });
  if (!response.ok) throw new Error('공개 카탈로그를 불러오지 못했습니다.');
  if (response.headers.get('Content-Type')?.split(';')[0]?.trim().toLowerCase() !== dcatMediaType(format)) {
    throw new Error('응답 형식이 올바르지 않습니다.');
  }
  const header = response.headers.get('X-Total-Count');
  const total = Number(header);
  if (header === null || !/^\d+$/.test(header) || !Number.isSafeInteger(total)) throw new Error('공개 데이터셋 수를 확인할 수 없습니다.');
  const body = await boundedText(response);
  return { body: format === 'jsonld' ? JSON.stringify(JSON.parse(body), null, 2) : body, total, page, format };
}
