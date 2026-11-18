import type { components } from './generated/api';
export type Dataset = components['schemas']['DatasetSummary'];
export type DatasetPage = components['schemas']['DatasetPage'];

export async function getDatasets(signal: AbortSignal): Promise<DatasetPage> {
  const response = await fetch('/api/catalog/v1/datasets?page=0&size=10', { signal, credentials: 'same-origin', cache: 'no-store' });
  if (!response.ok) throw new Error(`Catalog request failed (${response.status})`);
  return response.json() as Promise<DatasetPage>;
}
