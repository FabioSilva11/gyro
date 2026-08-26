export function advancePosition(position, yaw, movementX, movementY, deltaSeconds, speed = 3.5) {
  const dt = Math.max(0, Number(deltaSeconds) || 0);
  const rightX = Math.cos(yaw);
  const rightZ = -Math.sin(yaw);
  const forwardX = -Math.sin(yaw);
  const forwardZ = -Math.cos(yaw);
  const distance = speed * dt;

  return {
    x: position.x + (rightX * movementX + forwardX * movementY) * distance,
    z: position.z + (rightZ * movementX + forwardZ * movementY) * distance,
  };
}
