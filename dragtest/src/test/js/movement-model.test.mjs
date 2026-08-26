import test from 'node:test';
import assert from 'node:assert/strict';
import { advancePosition } from '../../main/assets/movement-model.mjs';

test('forward movement advances toward negative Z when yaw is zero', () => {
  const next = advancePosition({x: 0, z: 5}, 0, 0, 1, 0.5, 4);

  assert.deepEqual(next, {x: 0, z: 3});
});

test('backward movement advances toward positive Z when yaw is zero', () => {
  const next = advancePosition({x: 0, z: 5}, 0, 0, -1, 0.5, 4);

  assert.deepEqual(next, {x: 0, z: 7});
});

test('forward movement follows the current yaw', () => {
  const next = advancePosition({x: 0, z: 5}, Math.PI / 2, 0, 1, 0.5, 4);

  assert.ok(Math.abs(next.x + 2) < 1e-9);
  assert.ok(Math.abs(next.z - 5) < 1e-9);
});
