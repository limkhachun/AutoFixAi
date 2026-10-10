import { test } from 'node:test'
import assert from 'node:assert/strict'
import { quantityUnits } from '../src/quantity.ts'

test('sell limits distinguish a one-unit excess at 12 decimal places',()=>{
  const held=quantityUnits('1.000000000000')
  assert.equal(quantityUnits('1'),held)
  assert.ok(quantityUnits('0.999999999999')<held)
  assert.ok(quantityUnits('1.000000000001')>held)
  assert.equal(quantityUnits('0.000000000001'),1n)
  assert.equal(quantityUnits('0'),0n)
})
test('large holdings compare exactly beyond floating-point precision',()=>{
  assert.ok(quantityUnits('9007199254740993.000000000001')>quantityUnits('9007199254740993'))
  assert.equal(quantityUnits('0001.5000'),quantityUnits('1.5'))
})
test('invalid quantities cannot be interpreted as a sellable amount',()=>{
  for(const value of ['', '-1', '1e-12', 'NaN', '0.0000000000001', '1,5'])assert.equal(quantityUnits(value),null)
})
