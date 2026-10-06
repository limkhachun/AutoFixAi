export type Account = { id: number; email: string; language: 'en' | 'zh' }

export async function currentAccount(signal?: AbortSignal): Promise<Account | null> {
  const response = await fetch('/api/auth/me', { signal, credentials: 'same-origin' })
  if (response.status === 401) return null
  if (!response.ok) throw new Error('Unable to load account')
  return response.json()
}

export async function authRequest(path: string, body?: BodyInit, contentType?: string, method = 'POST') {
  const csrfResponse = await fetch('/api/auth/csrf', { credentials: 'same-origin' })
  if (!csrfResponse.ok) throw new Error('Unable to connect to the server')
  const csrf = await csrfResponse.json()
  const headers: Record<string, string> = { [csrf.headerName]: csrf.token }
  if (contentType) headers['Content-Type'] = contentType
  return fetch(path, { method, body, headers, credentials: 'same-origin' })
}

export type Trade = { id:number; asset:string; type:'BUY'|'SELL'; quantity:string; unitPrice:string; fee:string; occurredAt:string; currency:string }
export type Holding = { asset:string; quantity:string; costBasis:string; averageCost:string; realizedProfit:string }
export type Summary = { currency:string; costBasis:string; realizedProfit:string; holdings:Holding[]; transactionCount:number }
export type Market = { currency:string; provider:string; status:'LIVE'|'STALE'|'UNAVAILABLE'; issue:string|null; checkedAt:string|null; quotes:{asset:string;price:string|null;sourceUpdatedAt:string|null;fetchedAt:string|null;status:string}[] }
export type Valuation = {marketValue:string|null;unrealizedProfit:string|null;holdings:{asset:string;marketValue:string|null;unrealizedProfit:string|null}[]}
export type Portfolio = {trades:Trade[];summary:Summary;market:Market;valuation:Valuation}
export async function loadPortfolio(signal?: AbortSignal): Promise<Portfolio> {
  const response = await fetch('/api/portfolio/view',{signal})
  if(response.status===401) throw new Error('SESSION_EXPIRED')
  if(!response.ok) { const error=await response.json().catch(()=>({})); throw new Error(error.code??'LOAD_FAILED') }
  return response.json()
}
