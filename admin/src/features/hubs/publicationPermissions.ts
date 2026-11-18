import { hasAuthority, type AdminSession } from '../../lib/admin';

export const canPublishCandidates = (session: AdminSession) => ['ORG_EDITOR', 'ORG_REVIEWER', 'ORG_PUBLISHER'].every(role => hasAuthority(session, role));
