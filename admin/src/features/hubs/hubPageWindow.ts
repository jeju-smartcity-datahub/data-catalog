export const HUB_PAGE_SIZE = 10;

export function pageWindow(total: number, requestedPage: number, size = HUB_PAGE_SIZE) {
  const pages = Math.max(1, Math.ceil(total / size));
  const page = Math.max(0, Math.min(requestedPage, pages - 1));
  const start = total ? page * size + 1 : 0;
  return { page, pages, start, end: Math.min((page + 1) * size, total) };
}

export function pageRows<T>(rows: T[], requestedPage: number, size = HUB_PAGE_SIZE) {
  const window = pageWindow(rows.length, requestedPage, size);
  return { ...window, items: rows.slice(window.page * size, (window.page + 1) * size) };
}

export function selectionAfterPage(checked: string[], previousPage: number, nextPage: number, visibleIds: string[]) {
  return previousPage === nextPage ? checked.filter(id => visibleIds.includes(id)) : [];
}
