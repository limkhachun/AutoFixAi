import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { authRequest, currentAccount, loadPortfolio } from './api'
import type { Account, Trade, Portfolio } from './api'
import { copy } from './i18n'
import { formatMoney } from './currency'
import './App.css'

type View = 'overview' | 'transactions' | 'login'
const names:Record<string,string>={BTC:'Bitcoin',ETH:'Ethereum',SOL:'Solana'}
const demoHoldings=[
 {asset:'BTC',quantity:'0.02',costBasis:'4880.40',averageCost:'244020',realizedProfit:'0',price:262500},
 {asset:'ETH',quantity:'0.5',costBasis:'5048.40',averageCost:'10096.8',realizedProfit:'0',price:10920},
 {asset:'SOL',quantity:'10',costBasis:'5048.40',averageCost:'504.84',realizedProfit:'0',price:588},
]
const demoTrades:Trade[]=[
 {id:3,asset:'SOL',type:'BUY',quantity:'10',unitPrice:'504',fee:'8.40',occurredAt:'2026-10-03T00:00:00Z',currency:'MYR'},
 {id:2,asset:'ETH',type:'BUY',quantity:'0.5',unitPrice:'10080',fee:'8.40',occurredAt:'2026-10-02T00:00:00Z',currency:'MYR'},
 {id:1,asset:'BTC',type:'BUY',quantity:'0.02',unitPrice:'243600',fee:'8.40',occurredAt:'2026-10-01T00:00:00Z',currency:'MYR'},
]
export default function App() {
 const [lang,setLang]=useState<'en'|'zh'>('en')
 const [maxDate]=useState(()=>new Date().toISOString().slice(0,10))
 const [view,setView]=useState<View>('overview')
 const [account,setAccount]=useState<Account|null>(null)
 const [authReady,setAuthReady]=useState(false)
 const [register,setRegister]=useState(false)
 const [busy,setBusy]=useState(false)
 const [health,setHealth]=useState<'checking'|'connected'|'offline'>('checking')
 const [showForm,setShowForm]=useState(false)
 const [editing,setEditing]=useState<Trade|null>(null)
 const [deleteTarget,setDeleteTarget]=useState<Trade|null>(null)
 const [undoId,setUndoId]=useState<number|null>(null)
 const [message,setMessage]=useState('')
 const [revision,setRevision]=useState(0)
 const [data,setData]=useState<({owner:number;revision:number} & Portfolio)|null>(null)
 const [loadError,setLoadError]=useState<{owner:number;revision:number;message:string}|null>(null)
 const t=copy[lang]
 const text=(en:string,zh:string)=>lang==='en'?en:zh
 const money=(amount:string|number)=>formatMoney(amount,lang)
 const demo=authReady&&!account
 const loaded=!!account&&data?.owner===account.id&&data.revision===revision
 const loading=!!account&&!loaded&&!(loadError?.owner===account.id&&loadError.revision===revision)
 const holdings=demo?demoHoldings:loaded?data!.summary.holdings.filter(h=>Number(h.quantity)>0).map(h=>({...h,price:null,marketValue:data!.valuation.holdings.find(v=>v.asset===h.asset)?.marketValue??null})):[]
 const trades=demo?demoTrades:loaded?data!.trades:[]
 const cost=demo?'14977.20':loaded?data!.summary.costBasis:null
 const realized=demo?'0':loaded?data!.summary.realizedProfit:null
 const market=demo?16590:loaded?data!.valuation.marketValue:null
 const unrealized=demo?1612.8:loaded?data!.valuation.unrealizedProfit:null
 useEffect(()=>{
  const abort=new AbortController()
  fetch('/api/health',{signal:abort.signal}).then(async r=>{if(!r.ok||(await r.json()).status!=='UP')throw Error();setHealth('connected')}).catch(()=>{if(!abort.signal.aborted)setHealth('offline')})
  currentAccount(abort.signal).then(user=>{if(!abort.signal.aborted){setAccount(user);setAuthReady(true)}}).catch(()=>{if(!abort.signal.aborted)setAuthReady(true)})
  return ()=>abort.abort()
 },[])
 useEffect(()=>{
  if(!account)return
  const owner=account.id;const abort=new AbortController()
  loadPortfolio(abort.signal).then(payload=>{if(!abort.signal.aborted)setData({owner,revision,...payload})}).catch(error=>{
   if(!abort.signal.aborted)setLoadError({owner,revision,message:error.message})
  })
  return ()=>abort.abort()
 },[account,revision])
 useEffect(()=>{if(!account||busy)return;const timer=setInterval(()=>setRevision(n=>n+1),120000);return ()=>clearInterval(timer)},[account,busy])
 useEffect(()=>{document.documentElement.lang=lang==='en'?'en':'zh-CN'},[lang])
 function navigate(next:View){setView(next);setShowForm(false);setEditing(null);setDeleteTarget(null);setMessage('')}
 function handleFailure(status:number,code?:string):never {
  if(status===401){setAccount(null);setData(null);navigate('login');throw Error('SESSION_EXPIRED')}
  throw Error(code??(status===403?'CSRF_FAILED':'REQUEST_FAILED'))
 }
 function explain(error:unknown){
  const code=error instanceof Error?error.message:''
  return code==='OVERSELL'?text('This change would sell more than you held at that time. No changes were saved.','此更改会在交易当时卖出超过持仓的数量，未保存任何更改。'):
   code==='UNSUPPORTED_HISTORICAL_CURRENCY'?text('Your history contains USD records. MYR conversion must be configured before calculating this portfolio.','历史记录包含美元交易，需要先配置马币换算才能计算资产。'):
   code==='NOT_FOUND'?text('This transaction is no longer available. Refresh and try again.','此交易已不可用，请刷新后重试。'):
   code==='SESSION_EXPIRED'?text('Your session expired. Sign in again.','登录已过期，请重新登录。'):
   code==='INVALID_INPUT'?text('Check the asset, date, and numeric values (up to 12 decimal places).','请检查资产、日期和数值，最多支持12位小数。'):
   text('Unable to complete the request. Please try again.','无法完成请求，请重试。')
 }
 async function submitAuth(e:FormEvent<HTMLFormElement>){
  e.preventDefault();const form=e.currentTarget;const fields=new FormData(form)
  const email=String(fields.get('email'));const password=String(fields.get('password'))
  setBusy(true);setMessage('')
  try {
   if(register){
    const r=await authRequest('/api/auth/register',JSON.stringify({email,password,language:lang}),'application/json')
    if(!r.ok){setMessage(r.status===409?text('An account with this email already exists.','该邮箱已注册。'):text('Check your email and password requirements.','请检查邮箱和密码要求。'));return}
    form.reset();setRegister(false);setMessage(text('Account created. Sign in to continue.','账户已创建，请登录。'))
   }else{
    const r=await authRequest('/api/auth/login',new URLSearchParams({email,password}),'application/x-www-form-urlencoded')
    if(!r.ok){setMessage(text('Check your email and password and try again.','请检查邮箱和密码后重试。'));return}
    const user=await currentAccount();if(!user)throw Error('SESSION_EXPIRED')
    setData(null);setLoadError(null);setAccount(user);setLang(user.language);form.reset();navigate('overview')
   }
  }catch(error){setMessage(explain(error))}finally{setBusy(false)}
 }
 async function logout(){
  setBusy(true)
  try{const r=await authRequest('/api/auth/logout');if(!r.ok)handleFailure(r.status);setAccount(null);setData(null);setUndoId(null);navigate('login')}
  catch(error){setMessage(explain(error))}finally{setBusy(false)}
 }
 async function save(e:FormEvent<HTMLFormElement>){
  e.preventDefault();const fields=new FormData(e.currentTarget);const date=String(fields.get('date'))
  const occurredAt=editing?.occurredAt.slice(0,10)===date?editing.occurredAt:`${date}T00:00:00Z`
  const payload={asset:String(fields.get('asset')),type:String(fields.get('type')),quantity:String(fields.get('quantity')),unitPrice:String(fields.get('price')),fee:String(fields.get('fee')),occurredAt}
  setBusy(true);setMessage('')
  try{
   const r=await authRequest(editing?`/api/transactions/${editing.id}`:'/api/transactions',JSON.stringify(payload),'application/json',editing?'PUT':'POST')
   if(!r.ok){const error=await r.json().catch(()=>({}));handleFailure(r.status,error.code)}
   setRevision(n=>n+1);setShowForm(false);setEditing(null);setMessage(text('Transaction saved.','交易已保存。'))
  }catch(error){setMessage(explain(error))}finally{setBusy(false)}
 }
 async function removeOrRestore(id:number,restore=false){
  setBusy(true);setMessage('')
  try{
   const r=await authRequest(`/api/transactions/${id}${restore?'/restore':''}`,undefined,undefined,restore?'POST':'DELETE')
   if(!r.ok){const error=await r.json().catch(()=>({}));handleFailure(r.status,error.code)}
   setUndoId(restore?null:id);setDeleteTarget(null);setRevision(n=>n+1);setMessage(restore?text('Transaction restored.','交易已恢复。'):text('Transaction deleted. You can undo this below.','交易已删除，可在下方撤销。'))
  }catch(error){setMessage(explain(error))}finally{setBusy(false)}
 }
 function openForm(trade:Trade|null=null){
  if(!account){navigate('login');setMessage(text('Sign in to save your own transactions.','登录后即可保存您的交易。'));return}
  setEditing(trade);setShowForm(true);setDeleteTarget(null);setMessage('')
 }
 return <div className="app-shell">
  <aside className="sidebar"><a href="#" className="brand" onClick={e=>{e.preventDefault();navigate('overview')}}><span className="brand-mark">P</span>Portfolio<span className="brand-dot">.</span></a><p className="nav-caption">WORKSPACE</p><nav aria-label={t.nav}>{(['overview','transactions','login'] as View[]).map(item=><button key={item} aria-current={view===item?'page':undefined} className={view===item?'nav-item active':'nav-item'} disabled={busy} onClick={()=>navigate(item)}><span aria-hidden="true">{item==='overview'?'▦':item==='transactions'?'⇄':'↗'}</span>{t[item]}</button>)}</nav><div className="sidebar-foot"><span className={'status-dot '+health}/><span role="status">{t[health]}</span><small>Java · Spring Boot</small></div></aside>
  <div className="workspace"><header className="topbar"><span>Crypto Portfolio <span className="slash">/</span> {t[view]}</span><button className="language" onClick={()=>setLang(lang==='en'?'zh':'en')} aria-label={lang==='en'?'Switch to Chinese':'切换为英语'}>{lang==='en'?'中文':'English'}</button></header>
  <main><div className="demo-banner">{demo?text('Sample portfolio · Illustrative FX: USD 1 = MYR 4.20','示例资产 · 示例汇率：1美元 = 4.20马币'):text('Your transaction records · MYR','您的交易记录 · 马币')}</div>
  <div className="account-bar">{account?<><span>{text('Signed in as','当前账户')} <strong>{account.email}</strong></span><button className="text-button" disabled={busy} onClick={()=>void logout()}>{text('Sign out','退出登录')}</button></>:<span>{authReady?text('Sign in to manage your own portfolio.','登录后管理您的资产。'):text('Checking session…','正在检查登录状态…')}</span>}</div>
  {view==='login'?<div className="login-layout"><div><p className="eyebrow">CRYPTO PORTFOLIO</p><h1>{register?text('Start your portfolio.','开始管理您的资产。'):t.welcome}</h1><p className="muted">{t.subtitle}</p></div><form className="login-panel" onSubmit={submitAuth}><h2>{register?text('Create account','创建账户'):t.login}</h2><p className="muted">{t.loginNote}</p><label>{t.email}<input name="email" type="email" required autoComplete="username" maxLength={254} placeholder="you@example.com" disabled={busy}/></label><label>{t.password}<input name="password" type="password" required autoComplete={register?'new-password':'current-password'} minLength={register?12:1} maxLength={64} disabled={busy}/></label><button className="primary" disabled={busy}>{busy?text('Please wait…','请稍候…'):register?text('Create account','创建账户'):t.signin}</button><button type="button" className="text-button" disabled={busy} onClick={()=>{setRegister(!register);setMessage('')}}>{register?text('Already have an account? Sign in','已有账户？登录'):text('New here? Create account','还没有账户？创建账户')}</button><button type="button" className="text-button" onClick={()=>navigate('overview')}>{t.back}</button><p role="status">{message}</p></form></div>:<>
  <div className="page-heading"><div><p className="eyebrow">{view==='overview'?'PORTFOLIO OVERVIEW':'YOUR ACTIVITY'}</p><h1>{view==='overview'?t.title:t.history}</h1><p className="muted">{view==='overview'?t.subtitle:text('Your purchases and sales, ordered by their UTC transaction time.','按UTC交易时间排序的买卖记录。')}</p></div><button className="primary" disabled={busy||!authReady} aria-expanded={showForm} onClick={()=>openForm()}>+ {t.add}</button></div>
  <p role="status" className="feedback">{message}</p>
  {undoId!==null&&account&&<button className="text-button" disabled={busy} onClick={()=>void removeOrRestore(undoId,true)}>{text('Undo last deletion','撤销上次删除')}</button>}
  {loading&&<p role="status">{text('Loading your portfolio…','正在加载您的资产…')}</p>}
  {account&&loadError?.owner===account.id&&loadError.revision===revision&&<div role="alert" className="form-panel"><p>{explain(Error(loadError.message))}</p><button className="secondary" onClick={()=>setRevision(n=>n+1)}>{text('Retry','重试')}</button>{loadError.message==='SESSION_EXPIRED'&&<button className="text-button" onClick={()=>{setAccount(null);setData(null);navigate('login')}}>{t.login}</button>}</div>}
  {showForm&&<section className="form-panel"><h2>{editing?text('Edit transaction','编辑交易'):t.add}</h2><p className="muted">{text('Prices and fees are in MYR. Dates use UTC. Sales must be covered by holdings at that time.','单价和手续费以马币记录，日期使用UTC。卖出数量不得超过当时持仓。')}</p><form key={editing?.id??'new'} onSubmit={save}><div className="form-grid"><label>{t.asset}<select name="asset" defaultValue={editing?.asset??'BTC'} disabled={busy}>{Object.keys(names).map(symbol=><option key={symbol}>{symbol}</option>)}</select></label><label>{t.type}<select name="type" defaultValue={editing?.type??'BUY'} disabled={busy}><option value="BUY">{t.buy}</option><option value="SELL">{t.sell}</option></select></label><label>{t.quantity}<input name="quantity" type="number" step="0.000000000001" min="0.000000000001" required defaultValue={editing?.quantity} disabled={busy}/></label><label>{t.unit}<input name="price" type="number" step="0.000000000001" min="0.000000000001" required defaultValue={editing?.unitPrice} disabled={busy}/></label><label>{t.fee}<input name="fee" type="number" step="0.000000000001" min="0" defaultValue={editing?.fee??'0'} required disabled={busy}/></label><label>{t.date} (UTC)<input name="date" type="date" required min="1000-01-01" max={maxDate} defaultValue={editing?.occurredAt.slice(0,10)} disabled={busy}/></label></div><div className="form-actions"><button className="primary" disabled={busy}>{busy?text('Saving…','正在保存…'):text('Save transaction','保存交易')}</button><button type="button" className="secondary" disabled={busy} onClick={()=>{setShowForm(false);setEditing(null);setMessage('')}}>{t.cancel}</button></div></form></section>}
  {deleteTarget&&<section className="form-panel" role="alert"><h2>{text('Delete this transaction?','删除此交易？')}</h2><p>{deleteTarget.asset} · {deleteTarget.quantity} · {deleteTarget.occurredAt.slice(0,10)}</p><p className="muted">{text('Later transactions will be recalculated. You can undo this deletion.','后续交易将重新计算，删除后可撤销。')}</p><div className="form-actions"><button className="danger" disabled={busy} onClick={()=>void removeOrRestore(deleteTarget.id)}>{text('Delete transaction','删除交易')}</button><button className="secondary" disabled={busy} onClick={()=>setDeleteTarget(null)}>{t.cancel}</button></div></section>}
  {loaded&&<section className="market-status" aria-label={text('Market prices','市场价格')}><div><strong>{text('CoinGecko · MYR prices','CoinGecko · 马币价格')}</strong><p role="status">{data!.market.status==='LIVE'?text('Recent quotes','近期报价'):data!.market.status==='STALE'?text('Cached prices are stale. Valuation is an estimate.','缓存价格已过期，市值为估算值。'):text('Some asset prices are unavailable. Holdings without quotes show —.','部分资产报价不可用，缺少报价的持仓显示—。')}</p>{data!.market.issue&&<p>{data!.market.issue==='RATE_LIMITED'?text('Provider rate limit reached. Please try later.','报价服务请求受限，请稍后重试。'):text('Price refresh failed. Available cached quotes are retained.','价格刷新失败，仍保留可用的缓存报价。')}</p>}<small>{text('Provider quote times: ','报价时间：')}{data!.market.quotes.map(q=>q.asset+' '+(q.sourceUpdatedAt?new Date(q.sourceUpdatedAt).toLocaleString(lang==='en'?'en-MY':'zh-MY'):'—')).join(' · ')}</small><p><a href="https://www.coingecko.com/" target="_blank" rel="noreferrer">{text('Prices provided by CoinGecko','价格由CoinGecko提供')}</a></p></div><button className="secondary" disabled={busy||loading} onClick={()=>setRevision(n=>n+1)}>{text('Refresh prices','刷新价格')}</button><small>{text('Shared quotes refresh at most every 2 minutes; failed requests wait 1 minute.','共享报价最多每2分钟更新一次，失败后等待1分钟。')}</small></section>}
  {(demo||loaded)&&view==='overview'&&<><section className="metrics" aria-label={t.overview}><article className="metric lead"><p>{t.value}</p><strong>{market===null?'—':money(market)}</strong><small>{market===null?text('Awaiting market prices','等待市场价格'):text('MYR market value','马币市值')}</small></article><article className="metric"><p>{t.profit}</p><strong className={unrealized!==null&&Number(unrealized)>=0?'positive':''}>{unrealized===null?'—':money(unrealized)}</strong><small>{cost===null?'—':`${t.cost}: ${money(cost)}`}</small></article><article className="metric"><p>{t.realized}</p><strong className={Number(realized)<0?'negative':''}>{realized===null?'—':money(realized)}</strong><small>{text('Includes transaction fees','包含交易手续费')}</small></article><article className="metric"><p>{t.assets}</p><strong>{holdings.length.toString().padStart(2,'0')}</strong><small>{holdings.map(h=>h.asset).join(' · ')||text('No holdings yet','暂无持仓')}</small></article></section>
  <div className="portfolio-grid"><section className="panel holdings"><div className="panel-heading"><h2>{t.holdings}</h2><span className="muted">{text('MYR','马币')}</span></div>{holdings.length===0?<p className="empty-state">{text('Record your first purchase to start tracking your holdings.','记录第一笔买入交易，开始追踪持仓。')}</p>:<><p className="scroll-hint">{text("Swipe across the table to see all columns.","横向滑动表格可查看所有列。")}</p><div className="table-scroll" tabIndex={0} role="region" aria-label={text("Scrollable portfolio table","可横向滚动的资产表格")}><table><caption className="sr-only">{t.holdings}</caption><thead><tr><th>{t.asset}</th><th>{t.quantity}</th><th>{t.cost}</th><th>{text('Average cost','平均成本')}</th><th>{t.worth}</th></tr></thead><tbody>{holdings.map(h=><tr key={h.asset}><td><div className="asset-cell"><span className={'coin '+h.asset.toLowerCase()}>{h.asset[0]}</span><div><strong>{h.asset}</strong><small>{names[h.asset]}</small></div></div></td><td>{Number(h.quantity).toLocaleString(lang==='en'?'en-MY':'zh-MY',{maximumFractionDigits:12})}</td><td>{money(h.costBasis)}</td><td>{money(h.averageCost)}</td><td>{demo?money(Number(h.quantity)*(h.price??0)):('marketValue' in h&&h.marketValue!==null?money(h.marketValue as string):'—')}</td></tr>)}</tbody></table></div></>}</section>
  <section className="panel allocation"><h2>{demo?t.allocation:text('Allocation by cost','按成本分配')}</h2><div className="allocation-total"><span>{demo?t.value:t.cost}</span><strong>{money(demo?16590:cost??'0')}</strong></div>{holdings.map(h=>{const value=demo?Number(h.quantity)*(h.price??0):Number(h.costBasis);const max=demo?16590:Number(cost);return <div className="allocation-row" key={h.asset}><div><strong>{h.asset}</strong><span>{max>0?(value/max*100).toFixed(1):'0.0'}%</span></div><progress aria-label={`${h.asset} ${demo?t.allocation:text('cost allocation','成本分配')}`} value={value} max={max||1}/></div>})}</section></div></>}
  {(demo||loaded)&&<section className="panel activity"><div className="panel-heading"><h2>{view==='overview'?t.activity:t.history}</h2>{view==='overview'&&<button className="text-button" onClick={()=>navigate('transactions')}>{t.all} →</button>}</div>{trades.length===0?<p className="empty-state">{text('No transactions yet. Your saved purchases and sales will appear here.','暂无交易，保存后的买卖记录将显示于此。')}</p>:<><p className="scroll-hint">{text("Swipe across the table to see all columns.","横向滑动表格可查看所有列。")}</p><div className="table-scroll" tabIndex={0} role="region" aria-label={text("Scrollable portfolio table","可横向滚动的资产表格")}><table><caption className="sr-only">{t.history}</caption><thead><tr><th>{t.asset}</th><th>{t.type}</th><th>{t.quantity}</th><th>{t.unit}</th><th>{t.fee}</th><th>{t.date} (UTC)</th>{account&&<th>{text('Actions','操作')}</th>}</tr></thead><tbody>{trades.map(trade=><tr key={trade.id}><td><strong>{trade.asset}</strong></td><td><span className={trade.type==='BUY'?'buy-pill':'sell-pill'}>{trade.type==='BUY'?t.buy:t.sell}</span></td><td>{Number(trade.quantity).toLocaleString(lang==='en'?'en-MY':'zh-MY',{maximumFractionDigits:12})}</td><td>{money(trade.unitPrice)}</td><td>{money(trade.fee)}</td><td>{trade.occurredAt.slice(0,10)}</td>{account&&<td><div className="row-actions"><button className="text-button" disabled={busy} onClick={()=>openForm(trade)}>{text('Edit','编辑')}</button><button className="text-button" disabled={busy} onClick={()=>{setDeleteTarget(trade);setShowForm(false);setMessage('')}}>{text('Delete','删除')}</button></div></td>}</tr>)}</tbody></table></div></>}</section>}
  </>}
  <footer>{demo?text('Illustrative sample transactions and prices. Sign in to view your own records.','交易与价格均为示例，登录后可查看您的记录。'):text('Recorded transactions are not trades executed on an exchange. Market prices are indicative quotes; check their timestamps.','此处记录不会在交易所执行。市场报价仅供参考，请查看报价时间。')}</footer></main></div></div>
}

