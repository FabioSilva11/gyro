import * as THREE from './three.module.min.js';

const scene = new THREE.Scene();
scene.background = new THREE.Color(0x071426);
scene.fog = new THREE.Fog(0x071426, 18, 75);
const camera = new THREE.PerspectiveCamera(72, innerWidth / innerHeight, 0.1, 120);
camera.position.set(0, 1.7, 5);
camera.rotation.order = 'YXZ';
const renderer = new THREE.WebGLRenderer({antialias:false,powerPreference:'high-performance'});
renderer.setPixelRatio(Math.min(devicePixelRatio, 1.25));
renderer.setSize(innerWidth, innerHeight);
document.body.prepend(renderer.domElement);

scene.add(new THREE.HemisphereLight(0x9bdcff, 0x172033, 2.2));
const sun = new THREE.DirectionalLight(0xffffff, 2.2); sun.position.set(5, 10, 4); scene.add(sun);
const floor = new THREE.Mesh(new THREE.PlaneGeometry(160, 160), new THREE.MeshStandardMaterial({color:0x111827,roughness:0.92}));
floor.rotation.x = -Math.PI / 2; scene.add(floor);
scene.add(new THREE.GridHelper(160, 80, 0x38bdf8, 0x1e293b));

const colors = [0x38bdf8,0x22c55e,0xf59e0b,0xf43f5e,0xa78bfa];
for (let i=0;i<52;i++) {
  const w=1.2+Math.random()*2.4, h=1+Math.random()*5, d=1.2+Math.random()*2.4;
  const box = new THREE.Mesh(new THREE.BoxGeometry(w,h,d),new THREE.MeshStandardMaterial({color:colors[i%colors.length],roughness:.75}));
  const side=i%2===0?-1:1; box.position.set(side*(4+Math.random()*18),h/2,-5-Math.random()*60); scene.add(box);
}
for(let z=-8;z>-72;z-=8){
  const ring=new THREE.Mesh(new THREE.TorusGeometry(2.6,.08,6,28),new THREE.MeshBasicMaterial({color:0x7dd3fc}));
  ring.position.set(0,2.5,z); scene.add(ring);
}

let yaw=0, pitch=0, camActive=false, camPointerId=-1, lastX=0, lastY=0;
let downs=0,moves=0,ups=0,maxGap=0,lastMoveAt=0,frames=0,fps=0,lastFpsAt=performance.now();

let movActive=false, movPointerId=-1, movLastX=0, movLastY=0, movWorldX=0, movWorldY=0;
let mDowns=0,mMoves=0,mUps=0;
const MOVE_RADIUS=60;

const el=id=>document.getElementById(id);

function updateHud(){
  el('down').textContent=downs; el('move').textContent=moves; el('up').textContent=ups;
  el('ratio').textContent=(moves/Math.max(1,downs)).toFixed(1); el('gap').textContent=maxGap.toFixed(0);
  el('yaw').textContent=THREE.MathUtils.radToDeg(yaw).toFixed(1); el('pitch').textContent=THREE.MathUtils.radToDeg(pitch).toFixed(1); el('fps').textContent=fps;
  const state=el('state');
  if(camActive){state.textContent='CÂMERA • ARRASTO CONTÍNUO';state.className='good'}
  else if(downs>0 && moves/downs>=2){state.textContent='CÂMERA CONCLUÍDA';state.className='good'}
  else if(downs>2 && moves/downs<1){state.textContent='PARECE CLIQUE';state.className='bad'}
  else{state.textContent='AGUARDANDO ARRASTO';state.className=''}

  el('mDown').textContent=mDowns; el('mMove').textContent=mMoves; el('mUp').textContent=mUps;
  el('movX').textContent=movWorldX.toFixed(2); el('movY').textContent=movWorldY.toFixed(2);
  const ms=el('movState');
  if(movActive){ms.textContent='MOVIMENTO • ATIVO';ms.className='good'}
  else if(mDowns>0){ms.textContent='MOVIMENTO PRONTO';ms.className='good'}
  else{ms.textContent='AGUARDANDO MOVIMENTO';ms.className=''}

  const dot=el('movDot');
  dot.style.left=(50+movWorldX*50)+'%';
  dot.style.top=(50-movWorldY*50)+'%';
}

function isOnRightSide(x){return x > innerWidth * 0.4}
function isOnMovementCircle(x,y){
  const cx=80, cy=innerHeight/2;
  return Math.hypot(x-cx,y-cy) < MOVE_RADIUS + 30;
}

renderer.domElement.addEventListener('pointerdown',e=>{
  if(isOnMovementCircle(e.clientX,e.clientY)){
    movActive=true;movPointerId=e.pointerId;
    movLastX=e.clientX;movLastY=e.clientY;movWorldX=0;movWorldY=0;
    mDowns++;renderer.domElement.setPointerCapture?.(movPointerId);
    updateHud();e.preventDefault();return;
  }
  camActive=true;camPointerId=e.pointerId;lastX=e.clientX;lastY=e.clientY;
  lastMoveAt=performance.now();downs++;
  renderer.domElement.setPointerCapture?.(camPointerId);updateHud();e.preventDefault();
});

renderer.domElement.addEventListener('pointermove',e=>{
  if(movActive&&e.pointerId===movPointerId){
    const dx=e.clientX-movLastX, dy=e.clientY-movLastY;
    movLastX=e.clientX;movLastY=e.clientY;
    const magnitude=Math.hypot(dx,dy);
    const nx=dx/Math.max(1,magnitude), ny=-dy/Math.max(1,magnitude);
    const clamped=Math.min(1,Math.hypot(movWorldX+nx*0.05,movWorldY+ny*0.05));
    const angle=Math.atan2(movWorldY+ny*0.05,movWorldX+nx*0.05);
    movWorldX=clamped*Math.cos(angle);movWorldY=clamped*Math.sin(angle);
    mMoves++;updateHud();e.preventDefault();return;
  }
  if(!camActive||e.pointerId!==camPointerId)return;
  const now=performance.now(),dx=e.clientX-lastX,dy=e.clientY-lastY;
  maxGap=Math.max(maxGap,now-lastMoveAt);lastMoveAt=now;lastX=e.clientX;lastY=e.clientY;moves++;
  yaw-=dx*.0042; pitch=THREE.MathUtils.clamp(pitch-dy*.0042,-1.45,1.45);updateHud();e.preventDefault();
});

function endPointer(e){
  if(movActive&&e.pointerId===movPointerId){movActive=false;movPointerId=-1;mUps++;movWorldX=0;movWorldY=0;updateHud();e.preventDefault();return}
  if(!camActive||e.pointerId!==camPointerId)return;camActive=false;camPointerId=-1;ups++;updateHud();e.preventDefault();
}
renderer.domElement.addEventListener('pointerup',endPointer);renderer.domElement.addEventListener('pointercancel',endPointer);
el('reset').addEventListener('click',e=>{downs=moves=ups=maxGap=0;mDowns=mMoves=mUps=0;yaw=pitch=0;movWorldX=0;movWorldY=0;updateHud();e.stopPropagation()});

addEventListener('resize',()=>{camera.aspect=innerWidth/innerHeight;camera.updateProjectionMatrix();renderer.setSize(innerWidth,innerHeight)});
function animate(now){
  requestAnimationFrame(animate); camera.rotation.y=yaw;camera.rotation.x=pitch;renderer.render(scene,camera);frames++;
  if(now-lastFpsAt>=1000){fps=Math.round(frames*1000/(now-lastFpsAt));frames=0;lastFpsAt=now;updateHud()}
}
requestAnimationFrame(animate);updateHud();
setInterval(()=>{
  const data={downs,moves,ups,active:camActive,maxGapMs:+maxGap.toFixed(1),movesPerDown:+(moves/Math.max(1,downs)).toFixed(2),yaw:+THREE.MathUtils.radToDeg(yaw).toFixed(2),pitch:+THREE.MathUtils.radToDeg(pitch).toFixed(2),fps,
    movDowns:mDowns,movMoves:mMoves,movUps:mUps,movActive,movX:+movWorldX.toFixed(3),movY:+movWorldY.toFixed(3)};
  if(window.GyroTestBridge?.report) window.GyroTestBridge.report(JSON.stringify(data));
},500);
