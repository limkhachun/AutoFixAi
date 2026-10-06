import { test } from 'node:test'
import assert from 'node:assert/strict'
import { formatMoney } from '../src/currency.ts'
const normalized = (value) => value.replace(/\s+/g,' ')
test('MYR cents stay exact beyond JavaScript safe integer limits',()=>{
  assert.equal(normalized(formatMoney('9007199254740993.125','en')),'MYR 9,007,199,254,740,993.13')
})
test('rounding handles carry, small losses, and negative zero',()=>{
  assert.equal(normalized(formatMoney('999.995','en')),'MYR 1,000.00')
  assert.equal(normalized(formatMoney('-0.125','en')),'-MYR 0.13')
  assert.equal(normalized(formatMoney('-0.001','en')),'MYR 0.00')
})
