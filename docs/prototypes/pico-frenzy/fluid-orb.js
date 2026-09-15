/* Browser prototype only. The noise and spherical mapping come from pulse-orb.agsl. */
(() => {
'use strict';
const vertex = `attribute vec2 position;void main(){gl_Position=vec4(position,0.,1.);}`;
const fragment = `
precision highp float;
uniform vec2 resolution;
uniform float time, heat, core, original;
uniform vec3 primary, secondary;
float hash(vec2 p){p=fract(p*vec2(123.34,456.21));p+=dot(p,p+45.32);return fract(p.x*p.y);}
float noise(vec2 p){vec2 i=floor(p),f=fract(p);f=f*f*(3.-2.*f);return mix(mix(hash(i),hash(i+vec2(1,0)),f.x),mix(hash(i+vec2(0,1)),hash(i+vec2(1,1)),f.x),f.y);}
float cloud(vec2 p){float v=0.,a=.52;for(int i=0;i<5;i++){v+=a*noise(p);p=vec2(p.x*1.62-p.y*1.18,p.x*1.18+p.y*1.62)+13.7;a*=.48;}return v;}
float hash3(vec3 p){p=fract(p*.1031);p+=dot(p,p.yzx+33.33);return fract((p.x+p.y)*p.z);}
float noise3(vec3 p){
 vec3 i=floor(p),f=fract(p);f=f*f*(3.-2.*f);
 return mix(mix(mix(hash3(i),hash3(i+vec3(1,0,0)),f.x),mix(hash3(i+vec3(0,1,0)),hash3(i+vec3(1,1,0)),f.x),f.y),
            mix(mix(hash3(i+vec3(0,0,1)),hash3(i+vec3(1,0,1)),f.x),mix(hash3(i+vec3(0,1,1)),hash3(i+vec3(1,1,1)),f.x),f.y),f.z);
}
float cloud3(vec3 p){float v=0.,a=.52;for(int i=0;i<4;i++){v+=a*noise3(p);p=vec3(p.x*1.62-p.y*1.18,p.x*1.18+p.y*1.62,p.z*2.)+13.7;a*=.48;}return v;}
mat2 rotation(float a){return mat2(cos(a),-sin(a),sin(a),cos(a));}
void main(){
 float radius=min(resolution.x*.335,resolution.y*.397);
 vec2 p=(vec2(gl_FragCoord.x,resolution.y-gl_FragCoord.y)-resolution*.5)/radius;
 float t=time;
 p/=1.+sin(t*.72)*.009;
 float d=length(p),edge=1.5/radius;
 vec3 a=primary,b=secondary,pearl=mix(a,vec3(1),.88);
 if(d>1.63){gl_FragColor=vec4(0);return;}
 // Keep the original PICO spherical projection and continuously advected clouds.
 float z=sqrt(max(0.,1.-dot(p,p)));
 vec2 sphere=p/(.62+z*.38);
 if(original<.5)sphere=p*.94;
 vec2 drift=vec2(t*.15,-t*.11);
 vec2 q=vec2(cloud(sphere*2.1+drift),cloud(sphere*2.1-drift+7.3));
 float mist=cloud(sphere*3.3+q*2.5+vec2(-t*.12,t*.09));
 float filaments=cloud(sphere*10.+q*4.+drift*1.6);
 if(original>.5){
  float body=1.-smoothstep(1.-edge,1.+edge,d);
  float halo=exp(-max(d-1.,0.)*5.5)*.18+exp(-pow((d-1.035)*22.,2.))*.16;
  halo*=1.-smoothstep(1.05,1.62,d);
  float angle=atan(p.y,p.x);
  float ring=(1.-smoothstep(.001,.006,abs(d-1.255)))*.11+(1.-smoothstep(.001,.005,abs(d-1.43)))*.045;
  ring*=.6+.4*sin(angle*2.+t*.12);
  float ga=clamp(halo+ring,0.,.55);
  float front=p.y+.15*sin(p.x*2.8+t*.4)+(mist-.5)*.8;
  vec3 pigment=mix(a,b,clamp(mist*.55+p.x*.12,0.,1.));
  pigment*=.77+z*.3+mist*.16;
  vec3 col=mix(pigment,pearl,smoothstep(-.15,.29,front));
  float lace=exp(-abs(front-.05)*15.)*(.3+filaments*.7);
  col=mix(col,vec3(1),lace*.62);
  col+=pow(max(0.,1.-length(p-vec2(-.53,.21))*.85),5.)*.45;
  col=mix(col,pearl,pow(clamp(d,0.,1.),32.)*.6);
  gl_FragColor=vec4(clamp(col*body+a*ga*(1.-body),0.,1.),body+(1.-body)*ga);return;
 }
 // A turbulent outward volume: density defines both the body and its edge.
 // No fixed tongue geometry, evenly spaced rays, or circular outer boundary.
 // Embed angle on a cylinder, then advect radius. A feature at radius r appears
 // at r + .24 * dt next frame: all four sides flow away from the dark centre.
 // cos/sin embedding avoids a seam where the angle wraps around.
 vec2 direction=p/max(d,.001);
 vec3 outward=vec3(direction*1.30,(d-.25)*2.3-t*.55);
 vec2 curl=vec2(cloud3(outward),cloud3(outward+vec3(11.3,7.1,3.7)));
 vec3 stream=outward+vec3((curl.x-.5)*1.5,(curl.y-.5)*1.5,(curl.x+curl.y-1.)*.65);
 float billow=cloud3(stream*1.7);
 float detail=cloud3(stream*3.4+vec3(2.7,4.3,0.));
 float laceNoise=cloud3(stream*6.2+vec3(3.1,8.,1.7));
 mist=cloud3(outward*1.2+vec3(7.3,2.1,0.));
 vec2 shape=p+vec2((curl.x-.5)*.22,(curl.y-.5)*.2);
 float envelope=length(vec2(shape.x,(shape.y+.11)*.88));
 float boundary=.87-envelope+(billow-.46)*1.48*heat+(detail-.49)*.15;
 float mass=smoothstep(-.09,.30,boundary);
 mass=max(mass,exp(-pow((d-.46)*5.1,2.))*.78);
 float wisp=exp(-abs(boundary-.025)*32.);
 float folds=exp(-abs(billow-.49)*11.);
 float fine=exp(-abs(detail-.49)*35.);
 float filigree=exp(-abs(laceNoise-.48)*42.);
 float outside=1.-smoothstep(.93,1.47,envelope);
 // Thin luminous sheets pass in front of softer, dimmer fire behind them.
 float energy=mass*(.13+billow*.30+folds*.86+fine*.16+filigree*.05);
 energy+=wisp*(.33+fine*.36);
 energy*=outside;
 // The opening is torn into the luminous body; heat partially veils its edge.
 float eyeD=length(p-vec2(.025,-.015));
 float eyeEdge=.205*core+(mist-.48)*.10+(detail-.48)*.052;
 float eye=smoothstep(eyeEdge-.024,eyeEdge+.048,eyeD);
 float hotCore=exp(-pow((eyeD-eyeEdge-.115)*6.6,2.));
 energy+=hotCore*(.77+folds*.46+fine*.17)*mass;
 energy*=eye;
 float warm=smoothstep(.06,.48,energy);
 float hot=smoothstep(.52,1.13,energy);
 // Temperature falls outward: white-hot core, primary-colour body, secondary
 // thin outer flames. Both pigments still come directly from the chosen theme.
 float cooling=smoothstep(.40,1.05,d);
 vec3 flame=mix(b*.92,a,warm);
 flame=mix(flame,b,cooling*.69);
 flame=mix(flame,mix(a,vec3(1),.94),hot*(1.-cooling*.75));
 flame+=mix(a,vec3(1),.70)*pow(max(energy-.92,0.),1.3)*.22;
 float alpha=clamp(mass*.24+energy*.86+wisp*.17,0.,.99)*outside;
 float glow=exp(-max(envelope-.47,0.)*4.6)*.12;
 vec3 result=flame*alpha+mix(a,b,.35)*glow*(1.-alpha);
 alpha+=glow*(1.-alpha);
 // A dark centre with a dim warm haze, not a black disk with a drawn outline.
 vec3 cavity=mix(vec3(.023,.017,.006),a*.085,.35+.3*billow);
 result=mix(cavity,result,eye);
 alpha=mix(.98,alpha,eye);
 gl_FragColor=vec4(clamp(result,0.,1.),alpha);

}`;
const renderers=[];
let currentTime=0,lastTime=null,lastDraw=0,forcedTime=null,dirty=true,wasPaused=false;
const reduced=matchMedia('(prefers-reduced-motion: reduce)');
function hex(value){return [1,3,5].map(i=>parseInt(value.slice(i,i+2),16)/255);}
function create(orb){
 const canvas=document.createElement('canvas');canvas.className='fluid-canvas';canvas.setAttribute('aria-hidden','true');orb.replaceChildren(canvas);
 const gl=canvas.getContext('webgl',{alpha:true,antialias:false,premultipliedAlpha:true,powerPreference:'low-power'});
 if(!gl)throw new Error('瀏覽器未提供圖形加速，無法顯示流動球。');
 function compile(type,source){const shader=gl.createShader(type);gl.shaderSource(shader,source);gl.compileShader(shader);if(!gl.getShaderParameter(shader,gl.COMPILE_STATUS))throw new Error(gl.getShaderInfoLog(shader));return shader;}
 const program=gl.createProgram();gl.attachShader(program,compile(gl.VERTEX_SHADER,vertex));gl.attachShader(program,compile(gl.FRAGMENT_SHADER,fragment));gl.linkProgram(program);
 if(!gl.getProgramParameter(program,gl.LINK_STATUS))throw new Error(gl.getProgramInfoLog(program));
 gl.useProgram(program);const buffer=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,buffer);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-1,-1,1,-1,-1,1,-1,1,1,-1,1,1]),gl.STATIC_DRAW);
 const position=gl.getAttribLocation(program,'position');gl.enableVertexAttribArray(position);gl.vertexAttribPointer(position,2,gl.FLOAT,false,0,0);
 const u={};['resolution','time','heat','core','original','primary','secondary'].forEach(key=>u[key]=gl.getUniformLocation(program,key));
 let visible=true;
 new IntersectionObserver(entries=>{visible=entries[0].isIntersecting;dirty=true}).observe(orb);
 canvas.addEventListener('webglcontextlost',event=>{event.preventDefault();orb.setAttribute('data-render-error','圖形預覽已中斷，請重新整理');});
 renderers.push({canvas,gl,u,draw(settings){
  if(!visible)return;
  const size=Math.min(680,Math.round(orb.clientWidth*Math.min(devicePixelRatio||1,1.8)));
  if(canvas.width!==size||canvas.height!==size){canvas.width=size;canvas.height=size;gl.viewport(0,0,size,size);}
  gl.uniform2f(u.resolution,size,size);gl.uniform1f(u.time,currentTime);gl.uniform1f(u.heat,settings.heat);gl.uniform1f(u.core,settings.core);gl.uniform1f(u.original,settings.original);
  gl.uniform3fv(u.primary,settings.a);gl.uniform3fv(u.secondary,settings.b);gl.drawArrays(gl.TRIANGLES,0,6);
 }});
}
try{document.querySelectorAll('.orb').forEach(create);}catch(error){console.error(error);document.querySelectorAll('.orb').forEach(orb=>orb.setAttribute('data-render-error',error.message));}
function draw(){
 const style=getComputedStyle(document.documentElement);
 const settings={a:hex(style.getPropertyValue('--a').trim()),b:hex(style.getPropertyValue('--b').trim()),heat:Number(document.getElementById('heat').value)/100,core:Number(document.getElementById('core').value)/100,original:document.body.classList.contains('original-flow')?1:0};
 renderers.forEach(renderer=>renderer.draw(settings));dirty=false;
 window.__orbFrame={time:currentTime,...settings};
}
function tick(now){
 const paused=document.hidden||reduced.matches||document.body.classList.contains('paused')||forcedTime!==null;
 if(lastTime===null||wasPaused)lastTime=now;
 if(!paused)currentTime+=Math.min((now-lastTime)/1000,.06)*Number(document.getElementById('speed').value)/100;
 lastTime=now;wasPaused=paused;
 if((!paused&&now-lastDraw>=1000/30)||dirty){draw();lastDraw=now;window.__ready=true;}
 if(window.__recording&&currentTime>=20)forcedTime=currentTime=20;
 requestAnimationFrame(tick);
}
document.addEventListener('input',()=>dirty=true);document.addEventListener('click',()=>{dirty=true;if(!document.body.classList.contains('paused'))forcedTime=null;});
window.addEventListener('resize',()=>dirty=true);reduced.addEventListener('change',()=>dirty=true);
document.addEventListener('visibilitychange',()=>{lastTime=null;dirty=true;});
window.__seek=seconds=>{currentTime=seconds;forcedTime=seconds;draw();};
window.__orbRenderers=renderers;
document.fonts.ready.then(()=>requestAnimationFrame(tick));
})();
