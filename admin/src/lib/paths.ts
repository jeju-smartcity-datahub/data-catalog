const basePath = import.meta.env.BASE_URL.replace(/\/$/, '');

/** UI routes and public assets share the Vite deployment prefix. API paths stay at /api. */
export function adminUrl(path: string): string {
  return `${basePath}${path.startsWith('/') ? path : `/${path}`}`;
}

export function adminRoutePath(pathname: string): string {
  if (pathname === basePath || pathname === `${basePath}/`) return '/';
  return basePath && pathname.startsWith(`${basePath}/`) ? pathname.slice(basePath.length) : pathname;
}
