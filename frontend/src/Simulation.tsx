import { useCallback, useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { formatMoney } from './currency'
import { quantityUnits } from './quantity'
import { readSimulation, writeSimulation } from './simulation-api'
import type { AccountOverview, AccountPage, FundingCredit, FundingRequest, TradePreview, Wallet } from './simulation-api'

type Section = 'overview'|'trade'|'funding'|'admin'
type ReviewDraft = {request:FundingRequest;approve:boolean;amount:string;note:string}
type GrantDraft = {account:AccountOverview;amount:string;note:string;operationId:string}
const messages:Record<string,[string,string]>={
  INSUFFICIENT_CASH:['Not enough simulated MYR. Request funding first.','模拟余额不足，请先申请资金。'],
  OVERSELL:['The sell quantity exceeds your simulated holdings.','卖出数量超过您的模拟持仓。'],
  PRICE_UNAVAILABLE:['A recent price is unavailable. Refresh and try again later.','暂无可用近期报价，请刷新后重试。'],
  PRICE_CHANGED:['The price changed. Review a new quote before confirming.','报价已变化，请获取新报价并重新确认。'],
  WALLET_CHANGED:['Your balance changed. Review a new trade preview.','余额已变化，请重新预览并确认交易。'],
  QUOTE_EXPIRED:['This confirmation expired. Request a new preview.','确认报价已过期，请重新预览。'],
  REQUEST_PENDING:['You already have a pending funding request.','您已有一笔待审核的资金申请。'],
  ALREADY_REVIEWED:['This request was already reviewed. Refresh the list.','此申请已审核，请刷新列表。'],
  REASON_REQUIRED:['Give a reason when changing the amount or rejecting a request.','调整金额或拒绝申请时，必须填写原因。'],
  ADMIN_REQUIRED:['Only the designated admin can perform this action.','只有指定管理员可以执行此操作。'],
  ACCESS_DENIED:['Access denied. Refresh or sign in again.','无法执行操作，请刷新或重新登录。'],
  DUPLICATE_OPERATION:['This funding action was already used. Refresh the records before starting another grant.','此发放操作已使用，请先刷新记录，再决定是否发起新发放。'],
  TRADE_TOO_SMALL_OR_LARGE:['Trade value is outside the supported limits.','交易金额超出支持范围，卖出收入须至少为0.01马币。'],
  INVALID_INPUT:['Check the amount, quantity and required notes.','请检查金额、数量和必填说明。'],
  INVALID_QUANTITY:['Enter a quantity greater than zero using ordinary decimals, with up to 12 decimal places (for example, 0.5).','请输入大于0的普通小数，最多12位小数，例如0.5。'],
  NOT_FOUND:['The account, request or quote is no longer available. Refresh before retrying.','账户、申请或报价已不可用，请刷新后重试。'],
  SESSION_EXPIRED:['Your session expired. Sign in again using the sidebar.','登录已过期，请通过侧栏重新登录。'],
  REQUEST_FAILED:['Unable to confirm the result. Refresh the records before retrying.','未能确认操作结果，请先刷新记录，再决定是否重试。'],
  SAVED_REFRESH_FAILED:['The action was saved, but the page could not refresh. Refresh the records; do not submit another funding action.','操作已保存，但页面刷新失败。请刷新记录，不要重复发放资金。'],
  FUNDING_SENT:['Funding request sent.','资金申请已提交。'],
  APPROVED:['Funding approved and credited.','资金申请已批准，余额已入账。'],
  REJECTED:['Request rejected with the recorded reason.','申请已拒绝，原因已记录。'],
  GRANTED:['Simulated funding credited to the selected account.','模拟资金已发放至指定账户。'],
  TRADED:['Simulated trade completed.','模拟交易已完成。'],
}
function quantity(value:string){return value.includes('.')?value.replace(/0+$/,'').replace(/\.$/,''):value}
function sameAmount(a:string,b:string){
  const cents=(s:string)=>{if(!/^\d+(?:\.\d{1,2})?$/.test(s))return null;const [whole,part='']=s.split('.');return BigInt(whole)*100n+BigInt(part.padEnd(2,'0'))}
  const first=cents(a),second=cents(b)
  return first!==null&&first===second
}
export default function Simulation({lang}:{lang:'en'|'zh'}) {
  const [section,setSection]=useState<Section>('overview')
  const [wallet,setWallet]=useState<Wallet|null>(null)
  const [requests,setRequests]=useState<FundingRequest[]>([])
  const [credits,setCredits]=useState<FundingCredit[]>([])
  const [accounts,setAccounts]=useState<AccountPage|null>(null)
  const [searchInput,setSearchInput]=useState('')
  const [search,setSearch]=useState('')
  const [page,setPage]=useState(0)
  const [review,setReview]=useState<ReviewDraft|null>(null)
  const [grant,setGrant]=useState<GrantDraft|null>(null)
  const [grantConfirm,setGrantConfirm]=useState(false)
  const [asset,setAsset]=useState('BTC')
  const [direction,setDirection]=useState<'BUY'|'SELL'>('BUY')
  const [orderQuantity,setOrderQuantity]=useState('')
  const [preview,setPreview]=useState<TradePreview|null>(null)
  const [clock,setClock]=useState(()=>Date.now())
  const [busy,setBusy]=useState(false)
  const [error,setError]=useState('')
  const [notice,setNotice]=useState('')
  const text=(en:string,zh:string)=>lang==='en'?en:zh
  const money=(value:string|null)=>value===null?'—':formatMoney(value,lang)
  const date=(value:string|null)=>value?new Date(value).toLocaleString(lang==='en'?'en-MY':'zh-MY'):'—'
  const status=(s:FundingRequest['status'])=>s==='PENDING'?text('Pending','待审核'):s==='APPROVED'?text('Approved','已批准'):text('Rejected','已拒绝')
  const message=(code:string)=>text(...(messages[code]??messages.REQUEST_FAILED))
  const profitClass=(value:string|null)=>value===null?'':value.startsWith('-')?'negative':'positive'
  const left=preview?Math.max(0,Math.ceil((Date.parse(preview.expiresAt)-clock)/1000)):0
  const pending=wallet?.requests.some(r=>r.status==='PENDING')??false
  const selectedQuote=wallet?.market.quotes.find(q=>q.asset===asset)
  const sellQuantity=wallet?.holdings.find(h=>h.asset===asset)?.quantity??'0'
  const heldUnits=quantityUnits(sellQuantity)??0n
  const orderUnits=quantityUnits(orderQuantity)
  const selling=direction==='SELL'
  const overselling=selling&&orderUnits!==null&&orderUnits>heldUnits
  const noHoldings=selling&&heldUnits===0n
  const noteRequired=review?(!review.approve||!sameAmount(review.amount,review.request.amount)):false

  const load=useCallback(async(signal?:AbortSignal)=>{
    const w=await readSimulation<Wallet>('',signal)
    let r:FundingRequest[]=[],c:FundingCredit[]=[],a:AccountPage|null=null
    if(w.admin){
      [r,c,a]=await Promise.all([
        readSimulation<FundingRequest[]>('/admin/requests',signal),
        readSimulation<FundingCredit[]>('/admin/credits',signal),
        readSimulation<AccountPage>('/admin/accounts?search='+encodeURIComponent(search)+'&page='+page,signal),
      ])
    }
    if(!signal?.aborted){setWallet(w);setRequests(r);setCredits(c);setAccounts(a)}
  },[search,page])
  useEffect(()=>{
    const abort=new AbortController()
    Promise.resolve().then(()=>load(abort.signal)).catch(e=>{if(!abort.signal.aborted)setError(e instanceof Error?e.message:'REQUEST_FAILED')})
    return()=>abort.abort()
  },[load])
  useEffect(()=>{
    if(!preview)return
    const timer=setInterval(()=>setClock(Date.now()),1000)
    return()=>clearInterval(timer)
  },[preview])
  function navigate(next:Section){setSection(next);setPreview(null);setError('');setNotice('')}
  async function refresh(){
    setBusy(true);setError('');setNotice('');setPreview(null)
    try{await load()}catch(e){setError(e instanceof Error?e.message:'REQUEST_FAILED')}finally{setBusy(false)}
  }
  async function mutate(path:string,payload:unknown,success:string,onSaved?:()=>void){
    setBusy(true);setError('');setNotice('')
    let saved=false
    try{
      await writeSimulation(path,payload);saved=true;onSaved?.()
      await load();setNotice(success)
    }catch(e){
      const code=e instanceof Error?e.message:'REQUEST_FAILED'
      setError(saved?'SAVED_REFRESH_FAILED':code)
      if(['PRICE_CHANGED','WALLET_CHANGED','QUOTE_EXPIRED','PRICE_UNAVAILABLE','NOT_FOUND','OVERSELL'].includes(code)){
        setPreview(null);await load().catch(()=>{})
      }
    }finally{setBusy(false)}
  }
  function requestFunding(e:FormEvent<HTMLFormElement>){
    e.preventDefault();const form=e.currentTarget;const fields=new FormData(form)
    void mutate('/requests',{amount:String(fields.get('amount')),purpose:String(fields.get('purpose'))},'FUNDING_SENT',()=>form.reset())
  }
  async function prepareTrade(e:FormEvent<HTMLFormElement>){
    e.preventDefault();setError('');setNotice('')
    if(!/^[0-9]{1,16}(?:\.[0-9]{1,12})?$/.test(orderQuantity)||!/[1-9]/.test(orderQuantity)){
      setError('INVALID_QUANTITY');return
    }
    if(overselling||noHoldings){setError('OVERSELL');return}
    setBusy(true)
    try{
      const quote=await writeSimulation<TradePreview>('/trade-preview',{asset,type:direction,quantity:orderQuantity})
      setClock(Date.now());setPreview(quote)
    }catch(e){setError(e instanceof Error?e.message:'REQUEST_FAILED')}finally{setBusy(false)}
  }
  function confirmTrade(){
    if(!preview)return
    void mutate('/trades',{quoteId:preview.id},'TRADED',()=>{setPreview(null);setOrderQuantity('')})
  }
  function submitReview(e:FormEvent<HTMLFormElement>){
    e.preventDefault();if(!review)return
    void mutate('/admin/requests/'+review.request.id,{approve:review.approve,amount:review.approve?review.amount:null,note:review.note},
      review.approve?'APPROVED':'REJECTED',()=>setReview(null))
  }
  function submitGrant(e:FormEvent<HTMLFormElement>){
    e.preventDefault();if(!grant)return
    if(!/^\d+(?:\.\d{1,2})?$/.test(grant.amount)){setError('INVALID_INPUT');return}
    if(!grantConfirm){setGrantConfirm(true);return}
    void mutate('/admin/grants',{userId:grant.account.id,amount:grant.amount,note:grant.note,operationId:grant.operationId},
      'GRANTED',()=>{setGrant(null);setGrantConfirm(false)})
  }
  function searchAccounts(e:FormEvent<HTMLFormElement>){
    e.preventDefault();setError('');setPage(0)
    if(searchInput.trim()===search&&page===0){void refresh();return}
    setAccounts(null);setSearch(searchInput.trim())
  }
  function chooseAccount(account:AccountOverview){
    setError('');setNotice('');setGrantConfirm(false)
    setGrant({account,amount:'10000',note:'',operationId:crypto.randomUUID()})
  }
  function creditList(items:FundingCredit[],admin=false){
    return items.length===0?<p className="empty-state">{text('No funding credits yet.','暂无资金发放记录。')}</p>:
      <div className="simulation-records">{items.map(c=><article className="simulation-record" key={c.id}>
        <div className="simulation-record-heading"><strong>{money(c.amount)}</strong><span>{c.source==='DIRECT'?text('Direct grant','主动发放'):text('Approved request','申请获批')}</span></div>
        {admin&&<p>{text('Recipient','收款账户')}：{c.email}</p>}
        <p>{text('Note','备注')}：{c.note||text('No note recorded','未记录备注')}</p>
        <small>{text('Admin','操作人')}：{c.adminEmail??text('Historical record','历史记录')} · {date(c.createdAt)}</small>
      </article>)}</div>
  }
  return <section className="simulation">
    <div className="page-heading">
      <div><p className="eyebrow">{text('SIMULATED MYR','模拟马币')}</p><h1>{text('Your simulated assets','我的模拟资产')}</h1><p className="muted">{text('Request practice funds, trade crypto and track your results. All funds are simulated.','申请练习资金、买卖加密货币并查看盈亏，所有资金均为模拟资金。')}</p></div>
      <button className="secondary" disabled={busy} onClick={()=>void refresh()}>{busy?text('Please wait…','请稍候…'):text('Refresh','刷新')}</button>
    </div>
    <nav className="simulation-tabs" aria-label={text('Simulator sections','模拟交易功能')}>
      {(['overview','trade','funding',...(wallet?.admin?['admin']:[])] as Section[]).map(item=>
        <button type="button" key={item} className={section===item?'secondary selected':'secondary'} aria-pressed={section===item} disabled={busy} onClick={()=>navigate(item)}>
          {item==='overview'?text('Assets','资产概况'):item==='trade'?text('Buy / Sell','买卖交易'):item==='funding'?text('Funding','资金申请'):text('Admin','管理员')}
        </button>)}
    </nav>
    {error&&<div className="auth-feedback auth-error" role="alert"><span aria-hidden="true">!</span>{message(error)}</div>}
    {notice&&<p className="auth-feedback auth-notice" role="status">{message(notice)}</p>}
    {!wallet?<p role="status">{text('Loading simulated assets…','正在加载模拟资产…')}</p>:<>
      {section==='overview'&&<>
        <section className="metrics">
          <article className="metric lead"><p>{text('Available simulated cash','可用模拟余额')}</p><strong>{money(wallet.cash)}</strong><small>{text('Funding received','累计发放本金')}：{money(wallet.funded)}</small></article>
          <article className="metric"><p>{text('Crypto market value','持仓市值')}</p><strong>{money(wallet.marketValue)}</strong><small>{wallet.valuationStatus==='STALE'?text('Estimate from stale quotes','过期报价估算'):text('MYR valuation','马币估值')}</small></article>
          <article className="metric"><p>{text('Total simulated assets','模拟总资产')}</p><strong>{money(wallet.totalAssets)}</strong><small>{text('Cash + crypto market value','现金余额 + 持仓市值')}</small></article>
          <article className="metric"><p>{text('Total profit / loss','总盈亏')}</p><strong className={profitClass(wallet.totalProfit)}>{money(wallet.totalProfit)}</strong><small>{text('Total assets − funding received','总资产 − 累计发放本金')}</small></article>
          <article className="metric"><p>{text('Realized profit / loss','已实现盈亏')}</p><strong className={profitClass(wallet.realizedProfit)}>{money(wallet.realizedProfit)}</strong><small>{text('Sales proceeds − weighted cost','卖出收入 − 加权持仓成本')}</small></article>
          <article className="metric"><p>{text('Unrealized profit / loss','未实现盈亏')}</p><strong className={profitClass(wallet.unrealizedProfit)}>{money(wallet.unrealizedProfit)}</strong><small>{text('Market value − remaining cost','持仓市值 − 剩余成本')}</small></article>
        </section>
        {wallet.valuationStatus!=='LIVE'&&<p className="auth-feedback auth-notice" role="status">{wallet.valuationStatus==='STALE'?text('Some quotes are stale. Asset values and profit are estimates; stale quotes cannot be used for trading.','部分报价已过期，资产和盈亏为估算值，过期报价不可用于交易。'):text('Some held assets have no available price. Totals show — until prices are available.','部分持仓暂无报价，总资产和总盈亏暂显示—。')}</p>}
        <div className="form-actions"><button className="primary" onClick={()=>navigate('trade')}>{text('Buy / Sell crypto','买卖加密货币')}</button><button className="secondary" onClick={()=>navigate('funding')}>{text('Request simulated funding','申请模拟资金')}</button></div>
        <section className="panel"><h2>{text('Simulated holdings','模拟持仓')}</h2>
          {wallet.holdings.length===0?<p className="empty-state">{text('No simulated holdings yet.','暂无模拟持仓。')}</p>:<div className="table-scroll" tabIndex={0} role="region" aria-label={text('Simulated holdings table','模拟持仓表')}>
            <table><caption className="sr-only">{text('Simulated crypto holdings in MYR','模拟加密货币持仓，单位马币')}</caption><thead><tr>
              <th>{text('Asset','资产')}</th><th>{text('Quantity','数量')}</th><th>{text('Cost basis','持仓成本')}</th><th>{text('Average cost','平均成本')}</th><th>{text('Market value','市值')}</th><th>{text('Unrealized P/L','未实现盈亏')}</th>
            </tr></thead><tbody>{wallet.holdings.map(h=><tr key={h.asset}><td><strong>{h.asset}</strong></td><td>{quantity(h.quantity)}</td><td>{money(h.costBasis)}</td><td>{money(h.averageCost)}</td><td>{money(h.marketValue)}</td><td className={profitClass(h.unrealizedProfit)}>{money(h.unrealizedProfit)}</td></tr>)}</tbody></table>
          </div>}
          <p className="muted">{text('Admin funding is principal, not trading profit. These holdings are separate from your manual ledger.','管理员发放资金属于本金，不计为交易盈利。模拟持仓与手动账本独立。')}</p>
        </section>
      </>}
      {section==='trade'&&<>
        <div className="simulation-forms">
          <form className="form-panel simulation-form" onSubmit={prepareTrade}><h2>{text('Buy or sell crypto','买卖加密货币')}</h2>
            <p className="muted">{text('Available cash','可用余额')}：{money(wallet.cash)} · {text('Fee: MYR 0.00','手续费：MYR 0.00')}</p>
            <label>{text('Asset','资产')}<select value={asset} disabled={busy||!!preview} onChange={e=>setAsset(e.target.value)}><option>BTC</option><option>ETH</option><option>SOL</option></select></label>
            <label>{text('Action','操作')}<select value={direction} disabled={busy||!!preview} onChange={e=>setDirection(e.target.value as 'BUY'|'SELL')}><option value="BUY">{text('Buy','买入')}</option><option value="SELL">{text('Sell','卖出')}</option></select></label>
            {selling&&<div>
              <p className="muted">{text('Held / available to sell','持有数量 / 可卖数量')}：<strong>{quantity(sellQuantity)} {asset}</strong></p>
              <button className="secondary" type="button" disabled={busy||!!preview||noHoldings} onClick={()=>{setOrderQuantity(quantity(sellQuantity));setError('');setNotice('')}}>{text('Sell all','全部卖出')}</button>
              {noHoldings&&<p className="muted">{text('You have no '+asset+' available to sell.','您暂无可卖出的 '+asset+' 持仓。')}</p>}
            </div>}
            <label>{text('Crypto quantity','加密货币数量')}<input type="text" inputMode="decimal" value={orderQuantity} placeholder={text('For example, 0.5','例如：0.5')} pattern="[0-9]{1,16}([.][0-9]{1,12})?" title={text('Use ordinary decimals with up to 12 decimal places, for example 0.5.','请输入普通小数，最多12位小数，例如0.5。')} aria-invalid={overselling||undefined} aria-describedby={'simulation-quantity-help'+(overselling?' simulation-quantity-error':'')} required disabled={busy||!!preview} onChange={e=>setOrderQuantity(e.target.value)}/></label>
            <small id="simulation-quantity-help">{text('Enter a quantity greater than zero. Up to 12 decimal places; for example, 0.5 or 0.000000000001.','请输入大于0的数量，最多12位小数，例如0.5或0.000000000001。')}</small>
            {overselling&&<p id="simulation-quantity-error" className="auth-feedback auth-error" role="alert">{text('Sell quantity exceeds your holdings. You can sell at most '+quantity(sellQuantity)+' '+asset+'.','卖出数量超过可用持仓，最多可卖 '+quantity(sellQuantity)+' '+asset+'。')}</p>}
            <p className="muted">{text('Latest quote','近期报价')}：{selectedQuote?.price?money(selectedQuote.price):'—'}<br/>{text('Provider time','报价时间')}：{date(selectedQuote?.sourceUpdatedAt??null)}</p>
            {!preview&&<button className="primary" disabled={busy||selectedQuote?.status!=='LIVE'||overselling||noHoldings}>{text('Preview trade','预览交易')}</button>}
            <small>{text('The server prepares the price and settlement amount. You confirm before execution. MYR settles to cents; buys round up and sales round down.','服务器计算报价与成交金额，您确认后才执行。马币按分结算，买入支出向上取整，卖出收入向下取整。')}</small>
          </form>
          <section className="panel simulation-confirmation" aria-label={text('Trade confirmation','交易确认')}>
            <h2>{text('Confirm your trade','确认交易')}</h2>
            {!preview?<p className="muted">{text('Choose an asset and quantity, then preview the trade. No trade is executed until you confirm here.','选择资产和数量并预览，确认后才会执行交易。')}</p>:<>
              <p><strong>{preview.type==='BUY'?text('Buy','买入'):text('Sell','卖出')} {quantity(preview.quantity)} {preview.asset}</strong></p>
              <dl className="simulation-details">
                <dt>{text('Unit price','成交单价')}</dt><dd>{money(preview.price)}</dd>
                <dt>{text('Provider quote time','报价时间')}</dt><dd>{date(preview.sourceUpdatedAt)}</dd>
                <dt>{preview.type==='BUY'?text('Purchase cost','买入支出'):text('Sale proceeds','卖出收入')}</dt><dd>{money(preview.amount)}</dd>
                <dt>{text('Fee','手续费')}</dt><dd>{money(preview.fee)}</dd>
                <dt>{text('Balance before','交易前余额')}</dt><dd>{money(preview.cashBefore)}</dd>
                <dt>{text('Balance after','交易后余额')}</dt><dd><strong>{money(preview.cashAfter)}</strong></dd>
              </dl>
              <p role="status" className={left===0?'negative':'muted'}>{left===0?text('Confirmation expired. Request a new preview.','报价已过期，请重新预览。'):text('Confirmation expires in '+left+' seconds.','确认报价剩余 '+left+' 秒。')}</p>
              <p className="muted">{text('A changed price or balance requires a new confirmation. Executed simulated trades cannot be edited or deleted.','价格或余额变化后需要重新确认，已成交模拟交易不能修改或删除。')}</p>
              <div className="form-actions"><button className="primary" type="button" disabled={busy||left===0} onClick={confirmTrade}>{text('Confirm simulated trade','确认模拟交易')}</button><button className="secondary" type="button" disabled={busy} onClick={()=>setPreview(null)}>{text('Back to edit','返回修改')}</button></div>
            </>}
          </section>
        </div>
        <section className="panel"><h2>{text('Market quotes · MYR','市场报价 · 马币')}</h2><div className="simulation-quotes">{wallet.market.quotes.map(q=><p key={q.asset}><strong>{q.asset}</strong> · {money(q.price)} · {q.status==='LIVE'?text('Recent quote','近期报价'):text('Unavailable for trading','暂不可交易')}<br/><small>{date(q.sourceUpdatedAt)}</small></p>)}</div><a href="https://www.coingecko.com/" target="_blank" rel="noreferrer">{text('Prices provided by CoinGecko','价格由CoinGecko提供')}</a><p className="muted">{text('Quotes may be up to 10 minutes old. This is a practice market order simulator, not an exchange.','报价最长可能为10分钟前，这是市价交易练习模拟器。')}</p></section>
      </>}
      {section==='funding'&&<>
        <form className="form-panel simulation-form" onSubmit={requestFunding}><h2>{text('Request simulated MYR','申请模拟马币')}</h2>
          <label>{text('Requested amount (MYR)','申请金额（马币）')}<input name="amount" type="number" min="0.01" max="1000000" step="0.01" defaultValue="10000" required disabled={busy||pending}/></label>
          <label>{text('Purpose','申请用途')}<textarea name="purpose" maxLength={500} required rows={3} disabled={busy||pending} placeholder={text('For example: practice buying BTC and ETH','例如：练习买卖BTC和ETH')}/></label>
          <p className="muted">{pending?text('You have a pending request. Wait for review before submitting another.','您已有待审核申请，请等待审核后再提交。'):text('One pending request per account. The admin may approve a different amount and will record the reason.','每个账户仅允许一笔待审核申请，管理员调整金额时会记录原因。')}</p>
          <button className="primary" disabled={busy||pending}>{text('Submit request','提交申请')}</button>
        </form>
        <section className="panel"><h2>{text('My funding requests','我的资金申请')}</h2><p className="muted">{text('Latest 100 requests.','最近100笔申请。')}</p>
          {wallet.requests.length===0?<p className="empty-state">{text('No requests yet.','暂无申请。')}</p>:<div className="simulation-records">{wallet.requests.map(r=><article className="simulation-record" key={r.id}>
            <div className="simulation-record-heading"><strong>#{r.id} · {money(r.amount)}</strong><span className={'simulation-status '+r.status.toLowerCase()}>{status(r.status)}</span></div>
            <p>{text('Purpose','用途')}：{r.purpose||text('Historical request; purpose not recorded','历史申请，未记录用途')}</p>
            <p>{text('Actual credit','实际发放')}：{r.status==='APPROVED'?money(r.approvedAmount):r.status==='REJECTED'?money('0'):'—'}</p>
            {r.status!=='PENDING'&&<p>{text('Admin note','管理员备注')}：{r.reviewNote||text('No note recorded','未记录备注')}</p>}
            <small>{text('Requested','申请时间')}：{date(r.createdAt)}{r.reviewedAt&&<> · {text('Reviewed','审核时间')}：{date(r.reviewedAt)} · {r.reviewedBy??text('Historical record','历史记录')}</>}</small>
          </article>)}</div>}
        </section>
        <section className="panel"><h2>{text('Funding received','已收到的资金')}</h2><p className="muted">{text('Total received','累计本金')}：{money(wallet.funded)} · {text('Latest 100 credits','最近100笔发放')}</p>{creditList(wallet.credits)}</section>
      </>}
      {section==='admin'&&wallet.admin&&<>
        <section className="panel"><h2>{text('Admin · Funding requests','管理员 · 资金审批')}</h2><p className="muted">{text('Latest 100 requests, pending first. Changed amounts and rejected requests require a reason.','显示最多100笔申请，待审核优先；调整金额或拒绝申请必须填写原因。')}</p>
          {requests.length===0?<p className="empty-state">{text('No funding requests.','暂无资金申请。')}</p>:<div className="simulation-records">{requests.map(r=><article className="simulation-record" key={r.id}>
            <div className="simulation-record-heading"><strong>#{r.id} · {r.email}</strong><span className={'simulation-status '+r.status.toLowerCase()}>{status(r.status)}</span></div>
            <p>{text('Requested','申请金额')}：{money(r.amount)} · {text('Actual credit','实际发放')}：{r.status==='APPROVED'?money(r.approvedAmount):r.status==='REJECTED'?money('0'):'—'}</p>
            <p>{text('Purpose','用途')}：{r.purpose||text('Not recorded','未记录')}</p>
            {r.reviewNote&&<p>{text('Reason / note','原因 / 备注')}：{r.reviewNote}</p>}
            <small>{text('Requested','申请时间')}：{date(r.createdAt)}{r.reviewedAt&&<> · {text('Reviewed','审核时间')}：{date(r.reviewedAt)} · {r.reviewedBy??text('Historical record','历史记录')}</>}</small>
            {r.status==='PENDING'&&<button className="secondary" type="button" disabled={busy} onClick={()=>{setError('');setReview({request:r,approve:true,amount:r.amount,note:''})}}>{text('Review request','审核申请')}</button>}
          </article>)}</div>}
        </section>
        {review&&<form className="form-panel simulation-form" onSubmit={submitReview}>
          <h2>{text('Review request #','审核申请 #')}{review.request.id}</h2><p>{review.request.email} · {text('Requested','申请金额')} {money(review.request.amount)}</p>
          <div className="form-actions"><button className="secondary" type="button" aria-pressed={review.approve} disabled={busy} onClick={()=>setReview({...review,approve:true})}>{text('Approve','批准')}</button><button className="secondary" type="button" aria-pressed={!review.approve} disabled={busy} onClick={()=>setReview({...review,approve:false})}>{text('Reject','拒绝')}</button></div>
          {review.approve&&<label>{text('Actual funding amount (MYR)','实际发放金额（马币）')}<input type="number" min="0.01" max="1000000" step="0.01" value={review.amount} required disabled={busy} onChange={e=>setReview({...review,amount:e.target.value})}/></label>}
          <label>{noteRequired?text('Reason (required)','原因（必填）'):text('Admin note (optional)','管理员备注（可选）')}<textarea rows={3} maxLength={500} value={review.note} required={noteRequired} disabled={busy} onChange={e=>setReview({...review,note:e.target.value})}/></label>
          <div className="form-actions"><button className={review.approve?'primary':'danger'} disabled={busy}>{review.approve?text('Approve and credit funding','批准并发放资金'):text('Reject request','拒绝申请')}</button><button className="secondary" type="button" disabled={busy} onClick={()=>setReview(null)}>{text('Cancel','取消')}</button></div>
        </form>}
        <section className="panel"><h2>{text('Accounts and direct funding','账户与主动发放')}</h2>
          <form className="simulation-search" onSubmit={searchAccounts}><label>{text('Search accounts by email','按邮箱搜索账户')}<input type="search" value={searchInput} maxLength={254} disabled={busy} onChange={e=>setSearchInput(e.target.value)}/></label><button className="secondary" disabled={busy}>{text('Search','搜索')}</button></form>
          {!accounts?<p role="status">{text('Loading accounts…','正在加载账户…')}</p>:<>
            {accounts.accounts.length===0?<p className="empty-state">{text('No matching accounts.','没有匹配的账户。')}</p>:<div className="table-scroll" tabIndex={0} role="region" aria-label={text('Account balances','账户余额')}>
              <table><caption className="sr-only">{text('Account funding overview','账户资金概况')}</caption><thead><tr><th>{text('Account','账户')}</th><th>{text('Cash balance','现金余额')}</th><th>{text('Total funding','累计本金')}</th><th>{text('Pending requests','待审核申请')}</th><th>{text('Action','操作')}</th></tr></thead>
                <tbody>{accounts.accounts.map(a=><tr key={a.id}><td>{a.email}</td><td>{money(a.cash)}</td><td>{money(a.funded)}</td><td>{a.pendingRequests}</td><td><button className="text-button" type="button" disabled={busy} onClick={()=>chooseAccount(a)}>{text('Grant simulated MYR','发放模拟马币')}</button></td></tr>)}</tbody>
              </table>
            </div>}
            <div className="form-actions"><button className="secondary" type="button" disabled={busy||page===0} onClick={()=>{setAccounts(null);setPage(n=>n-1)}}>{text('Previous','上一页')}</button><span>{text('Page ','第 ')}{page+1}{text('',' 页')}</span><button className="secondary" type="button" disabled={busy||!accounts.more} onClick={()=>{setAccounts(null);setPage(n=>n+1)}}>{text('Next','下一页')}</button></div>
          </>}
        </section>
        {grant&&<form className="form-panel simulation-form" onSubmit={submitGrant}>
          <h2>{text('Direct simulated funding','主动发放模拟资金')}</h2><p><strong>{grant.account.email}</strong></p>
          <label>{text('Amount (MYR)','发放金额（马币）')}<input type="number" min="0.01" max="1000000" step="0.01" value={grant.amount} required disabled={busy||grantConfirm} onChange={e=>setGrant({...grant,amount:e.target.value})}/></label>
          <label>{text('Funding note (required)','发放备注（必填）')}<textarea rows={3} value={grant.note} required maxLength={500} disabled={busy||grantConfirm} onChange={e=>setGrant({...grant,note:e.target.value})}/></label>
          {grantConfirm&&<p className="auth-feedback auth-notice">{text('Confirm simulated funding: ','确认发放模拟资金：')}{money(grant.amount)} → {grant.account.email}</p>}
          <div className="form-actions"><button className="primary" disabled={busy}>{grantConfirm?text('Confirm funding','确认发放'):text('Review funding','核对发放')}</button><button className="secondary" type="button" disabled={busy} onClick={()=>grantConfirm?setGrantConfirm(false):setGrant(null)}>{grantConfirm?text('Back to edit','返回修改'):text('Cancel','取消')}</button></div>
        </form>}
        <section className="panel"><h2>{text('Admin funding history','管理员发放记录')}</h2><p className="muted">{text('Latest 100 credits, with recipient, amount, source, admin and time.','最近100笔发放，记录收款账户、金额、来源、操作人和时间。')}</p>{creditList(credits,true)}</section>
      </>}
      {(section==='overview'||section==='trade')&&<section className="panel"><h2>{text('Simulated trade history','模拟交易记录')}</h2><p className="muted">{text('Latest 100 immutable trades. Separate from the manual ledger.','最近100笔已成交交易，不能修改或删除，与手动账本独立。')}</p>
        {wallet.trades.length===0?<p className="empty-state">{text('No simulated trades yet.','暂无模拟交易。')}</p>:<div className="table-scroll" tabIndex={0} role="region" aria-label={text('Simulated trade history','模拟交易记录')}>
          <table><caption className="sr-only">{text('Simulated trades in MYR','模拟交易，单位马币')}</caption><thead><tr><th>{text('Action','操作')}</th><th>{text('Asset','资产')}</th><th>{text('Quantity','数量')}</th><th>{text('Unit price','单价')}</th><th>{text('Settled amount','成交金额')}</th><th>{text('Time','时间')}</th></tr></thead><tbody>{wallet.trades.map(t=><tr key={t.id}><td>{t.type==='BUY'?text('Buy','买入'):text('Sell','卖出')}</td><td><strong>{t.asset}</strong></td><td>{quantity(t.quantity)}</td><td>{money(t.price)}</td><td>{money(t.amount)}</td><td>{date(t.createdAt)}</td></tr>)}</tbody></table>
        </div>}
      </section>}
    </>}
  </section>
}
