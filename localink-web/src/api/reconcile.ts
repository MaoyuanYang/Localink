import { get } from './request'
import type { PageVO, ReconcileLogVO, RollbackFailureVO } from '../types/api'

export function adminPageReconcileLogs(status: number | null, page: number, size: number) {
  return get<PageVO<ReconcileLogVO>>('/api/reconcile/admin/page', { status: status ?? undefined, page, size })
}

export function adminPageRollbackFailures(page: number, size: number) {
  return get<PageVO<RollbackFailureVO>>('/api/reconcile/admin/failures', { page, size })
}
