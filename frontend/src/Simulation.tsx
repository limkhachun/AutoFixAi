import { useCallback, useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { authRequest } from './api'
import type { Market } from './api'
import { formatMoney } from './currency'

type Request = {id:number;email:string;amount:string;status:string;createdAt:string}
type Wallet = {admin:boolean;cash:string;holdings:{asset:string;quantity:string}[];requests:Request[];trades:{id:number;asset:string;type:string;quantity:string;price:string;amount:string;createdAt:string}[]}
export default function Simulation({lang}:{lang:'en'|'zh'}) {
 const [wallet,setWallet]=useState<Wallet|null>(null)
 const [requests,setRequests]=useState<Request[]>([])
 const [busy,setBusy]=useState(false)
 const [error,setError]=useState('')
 const [notice,setNotice]=useState('')
 const [market,setMarket]=useState<Market|null>(null)
 const text=(en:string,zh:string)=>lang==='en'?en:zh
 const money=(value:string)=>formatMoney(value,lang)
 const load=useCallback(async(signal?:AbortSignal)=>{
  const r=await fetch('/api/simulation',{signal});if(!r.ok)throw Error('LOAD_FAILED')
  const w:Wallet=await r.json()
  let pending:Request[]=[]
  if(w.admin){const a=await fetch('/api/simulation/admin/requests',{signal});if(!a.ok)throw Error('LOAD_FAILED');pending=await a.json()}
  if(!signal?.aborted){setWallet(w);setRequests(pending)}
  const q=await fetch('/api/simulation/quotes',{signal});if(q.ok){const quotes:Market=await q.json();if(!signal?.aborted)setMarket(quotes)}
 },[])
 useEffect(()=>{const abort=new AbortController();Promise.resolve().then(()=>load(abort.signal)).catch(()=>{if(!abort.signal.aborted)setError('LOAD_FAILED')});return()=>abort.abort()},[load])
 const errors:Record<string,[string,string]>={
  INSUFFICIENT_CASH:['Not enough simulated MYR. Request funding first.','模拟马币余额不足，请先申请资金。'],
  OVERSELL:['You cannot sell more simulated crypto than you hold.','卖出数量不能超过模拟持仓。'],
  PRICE_UNAVAILABLE:['A recent market price is unavailable. Refresh prices and try later.','暂无近期市场报价，请刷新价格后重试。'],
  REQUEST_PENDING:['You already have a pending funding request.','您已有待审核的资金申请。'],
  ALREADY_REVIEWED:['This request has already been reviewed. Refresh the list.','此申请已审核，请刷新列表。'],
  ADMIN_REQUIRED:['Only the configured admin can review funding requests.','只有指定管理员可以审核资金申请。'],
  TRADE_TOO_SMALL_OR_LARGE:['Trade value must be at least MYR 0.01 and within supported limits.','交易金额须至少为0.01马币且在支持范围内。'],
  INVALID_INPUT:['Check the amount and quantity.','请检查金额和数量。'],
 }
 async function mutate(path:string,payload:unknown,success:string) {
  setBusy(true);setError('');setNotice('')
  try{const r=await authRequest('/api/simulation'+path,JSON.stringify(payload),'application/json');if(!r.ok){const e=await r.json().catch(()=>({}));throw Error(e.code??'REQUEST_FAILED')}
   await load();setNotice(success)
  }catch(e){setError(e instanceof Error?e.message:'REQUEST_FAILED')}finally{setBusy(false)}
 }
 function funding(e:FormEvent<HTMLFormElement>){e.preventDefault();const f=new FormData(e.currentTarget);void mutate('/requests',{amount:String(f.get('amount'))},text('Funding request sent.','资金申请已提交。'))}
 function trade(e:FormEvent<HTMLFormElement>){e.preventDefault();const f=new FormData(e.currentTarget);void mutate('/trades',{asset:String(f.get('asset')),type:String(f.get('type')),quantity:String(f.get('quantity'))},text('Simulated trade completed.','模拟交易已完成。'))}
 return <section className="simulation">
  <div className="page-heading"><div><p className="eyebrow">{text('PAPER TRADING','模拟交易')}</p><h1>{text('Trading simulator','交易模拟器')}</h1><p className="muted">{text('Practice with simulated MYR. No real money or exchange orders.','使用模拟马币练习，不涉及真实资金或交易所订单。')}</p></div><button className="secondary" disabled={busy} onClick={()=>{setError('');void load().catch(()=>setError('LOAD_FAILED'))}}>{text('Refresh','刷新')}</button></div>
  {error&&<div className="auth-feedback auth-error" role="alert">{text(...(errors[error]??['Unable to load or complete the request. Refresh or sign in again.','无法加载或完成请求，请刷新或重新登录。']))}</div>}
  {notice&&<p className="auth-feedback auth-notice" role="status">{notice}</p>}
  {!wallet?<p role="status">{text('Loading simulator…','正在加载模拟器…')}</p>:<>
   <section className="metrics"><article className="metric lead"><p>{text('Available simulated cash','可用模拟余额')}</p><strong>{money(wallet.cash)}</strong><small>{text('Approved funding + sales − purchases','已批准资金 + 卖出收入 − 买入支出')}</small></article><article className="metric"><p>{text('Simulated holdings','模拟持仓')}</p>{wallet.holdings.filter(h=>Number(h.quantity)>0).map(h=><p key={h.asset}><strong>{h.asset}</strong> {h.quantity.replace(/\.?0+$/,'')}</p>)}{!wallet.holdings.some(h=>Number(h.quantity)>0)&&<p>{text('No holdings yet','暂无持仓')}</p>}</article></section>
   <div className="simulation-forms"><form className="form-panel" onSubmit={funding}><h2>{text('Request MYR from admin','向管理员申请马币')}</h2><label>{text('Amount (MYR)','金额（马币）')}<input name="amount" type="number" min="0.01" max="1000000" step="0.01" defaultValue="10000" required disabled={busy}/></label><button className="primary" disabled={busy||wallet.requests.some(r=>r.status==='PENDING')}>{text('Request funding','申请资金')}</button></form>
   <form className="form-panel" onSubmit={trade}><h2>{text('Buy or sell crypto','买卖加密货币')}</h2><p className="muted">{text('Executed at the server’s latest available CoinGecko quote (up to 10 minutes old). Zero fees; MYR settled to cents. Trades are final.','按服务器近期CoinGecko报价成交（最长10分钟），零手续费，马币按分结算，交易不可撤销。')}</p><div className="simulation-quotes">{market?.quotes.map(q=><p key={q.asset}>{q.asset} · {q.price?money(q.price):'—'} · {q.status==='LIVE'?text('Recent','近期'):text('Unavailable for trading','暂不可交易')}{q.sourceUpdatedAt&&<small> · {new Date(q.sourceUpdatedAt).toLocaleTimeString(lang==='en'?'en-MY':'zh-MY')}</small>}</p>)}<a href="https://www.coingecko.com/" target="_blank" rel="noreferrer">{text('Prices provided by CoinGecko','价格由CoinGecko提供')}</a></div><label>{text('Asset','资产')}<select name="asset" disabled={busy}><option>BTC</option><option>ETH</option><option>SOL</option></select></label><label>{text('Action','操作')}<select name="type" disabled={busy}><option value="BUY">{text('Buy','买入')}</option><option value="SELL">{text('Sell','卖出')}</option></select></label><label>{text('Crypto quantity','加密货币数量')}<input name="quantity" type="number" min="0.000000000001" step="0.000000000001" required disabled={busy}/></label><button className="primary" disabled={busy||!market?.quotes.some(q=>q.status==='LIVE')}>{text('Execute simulated trade','执行模拟交易')}</button></form></div>
   <section className="panel"><h2>{text('My funding requests','我的资金申请')}</h2>{wallet.requests.length===0&&<p>{text('No requests yet.','暂无申请。')}</p>}{wallet.requests.map(r=><p key={r.id}>#{r.id} · {money(r.amount)} · {r.status==='PENDING'?text('Pending','待审核'):r.status==='APPROVED'?text('Approved','已批准'):text('Rejected','已拒绝')}</p>)}</section>
   {wallet.admin&&<section className="panel"><h2>{text('Admin · Funding requests','管理员 · 资金申请')}</h2><p>{text('Approving credits the requested simulated MYR to that account.','批准后，申请的模拟马币将分配至该账户。')}</p>{requests.length===0&&<p>{text('No funding requests.','暂无资金申请。')}</p>}{requests.map(r=><div className="funding-row" key={r.id}><span>#{r.id} · {r.email} · {money(r.amount)} · {r.status==='PENDING'?text('Pending','待审核'):r.status==='APPROVED'?text('Approved','已批准'):text('Rejected','已拒绝')}</span>{r.status==='PENDING'&&<div className="row-actions"><button className="primary" disabled={busy} onClick={()=>void mutate(`/admin/requests/${r.id}`,{approve:true},text('Funding approved.','资金已批准。'))}>{text('Approve','批准')}</button><button className="secondary" disabled={busy} onClick={()=>void mutate(`/admin/requests/${r.id}`,{approve:false},text('Request rejected.','申请已拒绝。'))}>{text('Reject','拒绝')}</button></div>}</div>)}</section>}
   <section className="panel"><h2>{text('Simulated trade history','模拟交易记录')}</h2><p className="muted">{text('Latest 100 trades. Separate from your manual portfolio records.','最近100笔交易，与手动记录的资产独立。')}</p>{wallet.trades.map(t=><p key={t.id}>#{t.id} · {t.type==='BUY'?text('Buy','买入'):text('Sell','卖出')} {t.quantity} {t.asset} · {money(t.amount)} · {text('Unit price','单价')} {money(t.price)} · {new Date(t.createdAt).toLocaleString(lang==='en'?'en-MY':'zh-MY')}</p>)}{wallet.trades.length===0&&<p>{text('No simulated trades yet.','暂无模拟交易。')}</p>}</section>
  </>}
 </section>
}
