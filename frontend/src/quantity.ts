// Compare quantities as integer units of 0.000000000001, without floating-point rounding.
export function quantityUnits(value:string):bigint|null {
  if(!/^[0-9]+(?:\.[0-9]{1,12})?$/.test(value))return null
  const [whole,fraction='']=value.split('.')
  return BigInt(whole)*1000000000000n+BigInt(fraction.padEnd(12,'0'))
}
