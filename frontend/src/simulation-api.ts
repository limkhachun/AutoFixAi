import { authRequest } from './api'
import type { Market } from './api'

export type FundingRequest = {
  id:number; email:string; amount:string; purpose:string; status:'PENDING'|'APPROVED'|'REJECTED'
  approvedAmount:string|null; reviewNote:string; reviewedBy:string|null; createdAt:string; reviewedAt:string|null
}
export type FundingCredit = {id:number;email:string;amount:string;source:'REQUEST'|'DIRECT';note:string;adminEmail:string|null;createdAt:string}
export type SimulatedHolding = {asset:string;quantity:string;costBasis:string;averageCost:string;marketValue:string|null;unrealizedProfit:string|null}
export type SimulatedTrade = {id:number;asset:string;type:'BUY'|'SELL';quantity:string;price:string;amount:string;createdAt:string}
export type Wallet = {
  admin:boolean;cash:string;funded:string;marketValue:string|null;totalAssets:string|null
  realizedProfit:string;unrealizedProfit:string|null;totalProfit:string|null
  holdings:SimulatedHolding[];requests:FundingRequest[];credits:FundingCredit[];trades:SimulatedTrade[]
  valuationStatus:'LIVE'|'STALE'|'UNAVAILABLE';market:Market
}
export type AccountOverview = {id:number;email:string;cash:string;funded:string;pendingRequests:number;createdAt:string}
export type AccountPage = {accounts:AccountOverview[];page:number;more:boolean}
export type TradePreview = {
  id:string;asset:string;type:'BUY'|'SELL';quantity:string;price:string;amount:string;fee:string
  cashBefore:string;cashAfter:string;sourceUpdatedAt:string;fetchedAt:string;expiresAt:string
}
async function check(response:Response) {
  if(response.status===401)throw Error('SESSION_EXPIRED')
  if(!response.ok){const error=await response.json().catch(()=>({}));throw Error(error.code??(response.status===403?'ACCESS_DENIED':'REQUEST_FAILED'))}
}
export async function readSimulation<T>(path='',signal?:AbortSignal):Promise<T> {
  const response=await fetch('/api/simulation'+path,{signal,credentials:'same-origin'})
  await check(response)
  return response.json()
}
export async function writeSimulation<T=void>(path:string,payload:unknown):Promise<T> {
  const response=await authRequest('/api/simulation'+path,JSON.stringify(payload),'application/json')
  await check(response)
  const body=await response.text()
  return (body?JSON.parse(body):undefined) as T
}
