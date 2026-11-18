export type Protocol = 'https' | 'http';

export function splitEndpoint(input: string, protocol: Protocol = 'https') {
  const value = input.trim();
  const match = /^(https?):\/\//i.exec(value);
  return match ? { protocol: match[1].toLowerCase() as Protocol, address: value.slice(match[0].length) } : { protocol, address: value };
}

export function endpointValue(protocol: Protocol, address: string): string {
  const parsed = splitEndpoint(address, protocol);
  return parsed.address ? `${parsed.protocol}://${parsed.address}` : '';
}

export function endpointError(value: string, adapter: string, required: boolean): string {
  if (!value) return required ? '메타데이터 API 주소를 입력하세요.' : '';
  try {
    const url = new URL(value);
    if (!['https:', 'http:'].includes(url.protocol) || !url.hostname || url.username || url.password || url.search || url.hash || /\s/.test(value) || /:\/\//.test(value.slice(value.indexOf('://') + 3))) {
      return 'IP 또는 도메인과 포트를 확인하세요. 인증정보·검색조건은 주소에 넣지 않습니다.';
    }
    const terminal = adapter === 'NGSI_LD' ? 'entities' : 'datasets';
    if (url.pathname.split('/').some(part => decodeURIComponent(part).toLowerCase() === terminal)) return `/${terminal} 앞까지의 기본 주소를 입력하세요.`;
    return '';
  } catch { return 'IP 또는 도메인과 포트를 확인하세요.'; }
}
