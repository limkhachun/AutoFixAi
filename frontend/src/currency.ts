// Keep decimal strings exact until presentation; do not pass financial values through Number.
export function formatMoney(amount:string|number, language:'en'|'zh'):string {
  const raw=String(amount)
  const negative=raw.startsWith('-')
  const [integer,fraction='']=raw.replace(/^[-+]/,'').split('.')
  let cents=BigInt(integer)*100n+BigInt(fraction.slice(0,2).padEnd(2,'0'))
  if(Number(fraction[2]??'0')>=5) cents+=1n
  const whole=cents/100n
  const signedWhole=negative&&cents>0n?(whole===0n?-1n:-whole):whole
  const formatter=new Intl.NumberFormat(language==='en'?'en-MY':'zh-MY',{
    style:'currency',currency:'MYR',currencyDisplay:'code',minimumFractionDigits:2,maximumFractionDigits:2,
  })
  return formatter.formatToParts(signedWhole).map(part=>
    part.type==='fraction'?(cents%100n).toString().padStart(2,'0'):
    part.type==='integer'&&negative&&whole===0n&&cents>0n?'0':part.value,
  ).join('')
}
