'use strict';
const $ = (id) => document.getElementById(id);
const icons = (name) => `<svg aria-hidden="true"><use href="#i-${name}"></use></svg>`;
const escapeHTML = (text) => String(text ?? '').replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
const appState = {csrf:'',user:null,configured:false,favorites:[],results:[],history:[],users:[],stream:null,hls:null,view:'dashboard',busy:false};
let toastTimer = null;
function toast(message,bad=false){ const el=$('toast');el.textContent=message;el.classList.toggle('bad',bad);el.classList.add('show');clearTimeout(toastTimer);toastTimer=setTimeout(()=>el.classList.remove('show'),4300); }
async function api(path,{method='GET',body=null}={}){
  const options={method,credentials:'same-origin',headers:{}};
  if(method!=='GET') options.headers['X-CSRF-Token']=appState.csrf;
  if(body!==null){options.headers['Content-Type']='application/json';options.body=JSON.stringify(body);}
  const response=await fetch(path,options);
  let result={};try{result=await response.json();}catch{throw new Error('Réponse serveur invalide');}
  if(!response.ok)throw new Error(result.error||`Erreur HTTP ${response.status}`);
  return result;
}
function setBusy(busy){appState.busy=busy;for(const id of ['manualPlay','stopBtn','authSubmit']){if($(id))$(id).disabled=busy;}}
function updateIdentity(){
  const u=appState.user;if(!u)return;
  $('topUsername').textContent=u.username;$('topRole').textContent=u.role==='admin'?'Administrateur':'Utilisateur';
  $('accountName').textContent=u.username;$('accountRole').textContent=u.role==='admin'?'Administrateur':'Utilisateur';
  $('avatar').textContent=u.username[0].toUpperCase();$('accountAvatar').textContent=u.username[0].toUpperCase();
  document.querySelectorAll('.admin-only').forEach(el=>el.hidden=u.role!=='admin');
}
function showAuth(){
  $('appShell').hidden=true;$('authLayer').hidden=false;
  $('authTitle').textContent=appState.configured?'Connexion':'Créer le compte administrateur';
  $('authHint').textContent=appState.configured?'Retrouvez votre bibliothèque et vos favoris.':'Première installation : créez un administrateur avec la clé définie dans votre fichier .env.';
  $('setupField').hidden=appState.configured;
  $('authSubmit').textContent=appState.configured?'Se connecter':'Initialiser l’application';
  $('authPassword').setAttribute('autocomplete',appState.configured?'current-password':'new-password');
  // En connexion, ne pas bloquer un ancien compte dont le mot de passe serait plus court.
  // Pour créer un compte, le serveur et le formulaire imposent 10 caractères minimum.
  $('authPassword').minLength=appState.configured?0:10;
  $('authUsername').focus();
}
async function bootstrap(){
  try{
    const info=await api('/api/auth/session');
    appState.csrf=info.csrf;appState.user=info.user;appState.configured=info.configured;
    if(!info.user){showAuth();return;}
    $('authLayer').hidden=true;$('appShell').hidden=false;updateIdentity();
    await Promise.allSettled([loadFavorites(),loadStreams(),monitor()]);
    switchView(appState.view);
  }catch(err){$('authError').textContent=err.message;showAuth();}
}
$('authForm').addEventListener('submit',async(event)=>{
 event.preventDefault();$('authError').textContent='';setBusy(true);
 try{
  const body={username:$('authUsername').value.trim(),password:$('authPassword').value};
  if(!appState.configured)body.setup_token=$('authSetupToken').value;
  const data=await api(appState.configured?'/api/auth/login':'/api/auth/setup',{method:'POST',body});
  appState.csrf=data.csrf;appState.configured=true;appState.user=data.user;appState.view='dashboard';appState.results=[];appState.users=[];appState.history=[];$('authPassword').value='';$('authSetupToken').value='';
  await bootstrap();toast(`Bienvenue ${data.user.username} !`);
 }catch(err){$('authError').textContent=err.message;}finally{setBusy(false);}
});
const views={dashboard:['Tableau de bord','Recherchez, diffusez et retrouvez vos flux en toute simplicité.'],search:['Recherche','Explorez les chaînes et flux de la playlist publique.'],favorites:['Mes favoris','Votre bibliothèque personnelle, classée par groupes.'],history:['Historique','Les dernières lectures de votre compte.'],users:['Utilisateurs','Gérez les accès à votre serveur.'],settings:['Paramètres','Sécurité et préférences de votre compte.']};
function switchView(name){
 if(!(name in views) || (name==='users'&&appState.user?.role!=='admin'))return;
 appState.view=name;for(const el of document.querySelectorAll('.view'))el.hidden=el.id!==`view-${name}`;
 for(const el of document.querySelectorAll('.navitem'))el.classList.toggle('active',el.dataset.view===name);
 $('pageTitle').textContent=views[name][0];$('breadcrumb').textContent=views[name][0];$('pageSubtitle').textContent=views[name][1];
 $('sidebar').classList.remove('open');
 if(name==='favorites')renderFavorites();
 if(name==='history')loadHistory();
 if(name==='users')loadUsers();
 if(name==='search' && appState.results.length)renderSearch(appState.results);
}
document.addEventListener('click',event=>{
 const viewButton=event.target.closest('[data-view]');if(viewButton){switchView(viewButton.dataset.view);return;}
 const control=event.target.closest('[data-action]');if(!control)return;
 const action=control.dataset.action, id=control.dataset.id;
 if(action==='play-result'){ const entry=appState.results.find(r=>r.content_id===id);if(entry)play(entry.content_id,entry.name); }
 else if(action==='star-result'){const entry=appState.results.find(r=>r.content_id===id);const existing=appState.favorites.find(f=>f.content_id===id);if(existing)openFavorite(existing);else if(entry)openFavorite({content_id:entry.content_id,name:entry.name,group_name:entry.category||'Mes favoris'});}
 else if(action==='play-favorite'){const entry=appState.favorites.find(f=>String(f.id)===id);if(entry){switchView('dashboard');play(entry.content_id,entry.name);}}
 else if(action==='edit-favorite'){const entry=appState.favorites.find(f=>String(f.id)===id);if(entry)openFavorite(entry);}
 else if(action==='delete-favorite'){deleteFavorite(id);}
 else if(action==='play-history'){const entry=appState.history.find(h=>`${h.played_at}-${h.content_id}`===id);if(entry){switchView('dashboard');play(entry.content_id,entry.name);}}
 else if(action==='delete-user')deleteUser(id);
});
$('menuBtn').addEventListener('click',()=> $('sidebar').classList.toggle('open'));
$('profileBtn').addEventListener('click',()=> switchView('settings'));
$('refreshStatus').addEventListener('click',()=> monitor(true));
async function monitor(showError=false){
 if(!appState.user)return;
 try{
  const info=await api('/api/monitor');const {vpn,engine}=info;
  $('vpnText').textContent=vpn.connected?'Tunnel WireGuard actif':'Tunnel interrompu ou inaccessible';
  $('vpnIp').textContent=vpn.public_ip||'Non disponible';$('vpnGuard').textContent=vpn.connected?'Contrôlée par Gluetun':'Lecture bloquée';
  $('vpnPill').textContent=vpn.connected?'Connecté':'Déconnecté';$('vpnPill').className=`pill ${vpn.connected?'':'bad'}`;
  $('vpnDot').className=`status-dot ${vpn.connected?'good':'bad'}`;
  $('topVpn').innerHTML=`<span class="status-dot ${vpn.connected?'good':'bad'}"></span>${vpn.connected?'VPN connecté':'VPN indisponible'}`;
  $('engineText').textContent=engine.online?'Moteur accessible sur le réseau VPN':'Moteur non joignable';
  $('engineVersion').textContent=engine.version||'—';$('engineLatency').textContent=engine.latency_ms!==null?`${engine.latency_ms} ms`:'—';
  $('enginePill').textContent=engine.online?'En ligne':'Hors ligne';$('enginePill').className=`pill ${engine.online?'':'bad'}`;
  $('engineDot').className=`status-dot ${engine.online?'good':'bad'}`;
  $('streamsCount').textContent=`${info.streams} / ${info.max_streams}`;
  $('sideSystem').textContent=(vpn.connected&&engine.online)?'Système opérationnel':'Système à vérifier';
  $('sideDot').className=`status-dot ${vpn.connected&&engine.online?'good':'bad'}`;
  if(!vpn.connected&&appState.stream){resetVideo();appState.stream=null;playerStatus('VPN indisponible : lecture arrêtée.',true);}
 }catch(err){
  $('vpnText').textContent='Surveillance indisponible';$('vpnPill').textContent='Inconnu';$('vpnPill').className='pill unknown';
  $('vpnDot').className='status-dot bad';$('topVpn').textContent='Statut inconnu';
  if(showError)toast(err.message,true);
 }
}
async function search(query){
 query=query.trim();if(query.length<2){toast('Saisissez au moins 2 caractères.',true);return;}
 $('searchHint').textContent='Recherche en cours…';
 $('searchResults').innerHTML='<div class="empty mini">Recherche dans la playlist…</div>';
 $('searchResults2').innerHTML='<div class="empty mini">Recherche dans la playlist…</div>';
 try{
  const data=await api(`/api/search?q=${encodeURIComponent(query)}`);appState.results=data.results;renderSearch(data.results);
  $('searchHint').textContent=`${data.total} résultat(s) · ${data.results.length} affiché(s) · ${data.source}${data.warning?' · données en cache':''}`;
  if(data.warning)toast('Source momentanément inaccessible : résultats en cache.',true);
 }catch(err){appState.results=[];$('searchHint').textContent='Recherche indisponible.';const msg=`<div class="empty mini">${escapeHTML(err.message)}</div>`;$('searchResults').innerHTML=msg;$('searchResults2').innerHTML=msg;toast(err.message,true);}
}
function renderSearch(results){
 const ids=new Set(appState.favorites.map(f=>f.content_id));
 const html=results.length?results.map(entry=>`<div class="result-item"><div class="result-symbol">${icons('monitor')}</div><div class="item-body"><strong title="${escapeHTML(entry.name)}">${escapeHTML(entry.name)}</strong><small>${escapeHTML(entry.category||'Divers')} · ${escapeHTML(entry.source||'Playlist publique')}</small></div><div class="item-actions"><button class="tiny-btn star" title="${ids.has(entry.content_id)?'Déjà dans les favoris':'Ajouter aux favoris'}" data-action="star-result" data-id="${entry.content_id}">${icons('star')}</button><button class="tiny-btn play" title="Lire" data-action="play-result" data-id="${entry.content_id}">${icons('play')}</button></div></div>`).join(''):'<div class="empty mini">Aucun résultat trouvé pour cette recherche.</div>';
 $('searchResults').innerHTML=html;$('searchResults2').innerHTML=html;
}
$('searchForm').addEventListener('submit',event=>{event.preventDefault();$('searchQuery2').value=$('searchQuery').value;search($('searchQuery').value);});
$('searchForm2').addEventListener('submit',event=>{event.preventDefault();$('searchQuery').value=$('searchQuery2').value;search($('searchQuery2').value);});
async function loadFavorites(){try{const data=await api('/api/favorites');appState.favorites=data.favorites;renderFavorites();}catch(err){toast(err.message,true);}}
function favoriteItem(entry){return `<div class="fav-item"><div class="result-symbol">${icons('star')}</div><div class="item-body"><strong title="${escapeHTML(entry.name)}">${escapeHTML(entry.name)}</strong><small>${escapeHTML(entry.notes||entry.content_id.slice(0,12)+'…')}</small></div><div class="item-actions"><button class="tiny-btn play" title="Lire" data-action="play-favorite" data-id="${entry.id}">${icons('play')}</button><button class="tiny-btn" title="Modifier" data-action="edit-favorite" data-id="${entry.id}">${icons('pencil')}</button><button class="tiny-btn" title="Supprimer" data-action="delete-favorite" data-id="${entry.id}">${icons('trash')}</button></div></div>`;}
function renderFavorites(){
 const values=appState.favorites;
 const render=(arr)=>{
  if(!arr.length)return '<div class="empty mini">Aucun favori enregistré. Cliquez sur l’étoile d’un résultat pour commencer.</div>';
  const groups=new Map();for(const f of arr){if(!groups.has(f.group_name))groups.set(f.group_name,[]);groups.get(f.group_name).push(f);}
  return [...groups].map(([name,items])=>`<div class="group-title">${escapeHTML(name)} <span>(${items.length})</span></div>${items.map(favoriteItem).join('')}`).join('');
 };
 $('favoritesList').innerHTML=render(values.slice(0,6));
 const q=$('filterFavorites').value.trim().toLocaleLowerCase();
 $('favoritesFullList').innerHTML=render(values.filter(item=>(item.name+' '+item.group_name+' '+item.notes).toLocaleLowerCase().includes(q)));
 if(appState.results.length)renderSearch(appState.results);
}
$('filterFavorites').addEventListener('input',renderFavorites);
function openFavorite(entry={}){
 $('modalTitle').textContent=entry.id?'Modifier le favori':'Ajouter aux favoris';$('modalId').value=entry.id||'';
 $('favoriteName').value=entry.name||'';$('favoriteHash').value=entry.content_id||'';
 $('favoriteGroup').value=entry.group_name||'Mes favoris';$('favoriteNotes').value=entry.notes||'';
 $('modalLayer').hidden=false;$('favoriteName').focus();
}
function closeFavorite(){$('modalLayer').hidden=true;}
$('newFavorite').addEventListener('click',()=>openFavorite());$('newFavorite2').addEventListener('click',()=>openFavorite());
$('modalClose').addEventListener('click',closeFavorite);
$('modalLayer').addEventListener('click',event=>{if(event.target===$('modalLayer'))closeFavorite();});
$('modalForm').addEventListener('submit',async event=>{
 event.preventDefault();const id=$('modalId').value;
 const body={name:$('favoriteName').value,content_id:$('favoriteHash').value,group_name:$('favoriteGroup').value,notes:$('favoriteNotes').value};
 try{await api(id?`/api/favorites/${id}`:'/api/favorites',{method:id?'PATCH':'POST',body});closeFavorite();await loadFavorites();toast(id?'Favori modifié.':'Favori enregistré.');}catch(err){toast(err.message,true);}
});
async function deleteFavorite(id){if(!confirm('Supprimer ce favori ?'))return;try{await api(`/api/favorites/${id}`,{method:'DELETE'});await loadFavorites();toast('Favori supprimé.');}catch(err){toast(err.message,true);}}
async function loadHistory(){try{
 const data=await api('/api/history');appState.history=data.history;
 $('historyList').innerHTML=data.history.length?data.history.map(item=>`<div class="history-item"><div class="result-symbol">${icons('history')}</div><div class="item-body"><strong>${escapeHTML(item.name)}</strong><small>${new Date(item.played_at*1000).toLocaleString('fr-FR')}</small></div><button class="tiny-btn play" data-action="play-history" data-id="${item.played_at}-${item.content_id}" title="Relancer">${icons('play')}</button></div>`).join(''):'<div class="empty">Aucune lecture dans votre historique.</div>';
 }catch(err){toast(err.message,true);}}
async function loadUsers(){try{
  const data=await api('/api/users');appState.users=data.users;
  $('usersList').innerHTML=data.users.map(u=>`<div class="user-item"><span class="avatar">${escapeHTML(u.username[0].toUpperCase())}</span><div class="item-body"><strong>${escapeHTML(u.username)}${u.id===appState.user.id?' (vous)':''}</strong><small>Dernière connexion : ${u.last_login?new Date(u.last_login*1000).toLocaleString('fr-FR'):'Jamais'}</small></div><span class="user-role ${u.role==='user'?'member':''}">${u.role==='admin'?'Admin':'Utilisateur'}</span>${u.id!==appState.user.id?`<button class="tiny-btn" data-action="delete-user" data-id="${u.id}" title="Supprimer">${icons('trash')}</button>`:''}</div>`).join('');
 }catch(err){toast(err.message,true);}}
$('newUser').addEventListener('click',()=>{$('userLayer').hidden=false;$('newUsername').focus();});
$('userClose').addEventListener('click',()=>{$('userLayer').hidden=true;});
$('userLayer').addEventListener('click',event=>{if(event.target===$('userLayer'))$('userLayer').hidden=true;});
$('userForm').addEventListener('submit',async event=>{event.preventDefault();try{
 await api('/api/users',{method:'POST',body:{username:$('newUsername').value,password:$('newUserPassword').value,role:$('newUserRole').value}});
 $('userLayer').hidden=true;$('userForm').reset();await loadUsers();toast('Utilisateur créé.');
 }catch(err){toast(err.message,true);}});
async function deleteUser(id){if(!confirm('Supprimer cet utilisateur et ses favoris ?'))return;try{await api(`/api/users/${id}`,{method:'DELETE'});await loadUsers();toast('Utilisateur supprimé.');}catch(err){toast(err.message,true);}}
$('passwordForm').addEventListener('submit',async event=>{event.preventDefault();try{
 await api('/api/auth/password',{method:'POST',body:{old_password:$('oldPassword').value,new_password:$('newPassword').value}});$('passwordForm').reset();toast('Mot de passe modifié.');
 }catch(err){toast(err.message,true);}});
async function signOut(){try{
 if(appState.stream)await stopStream();await api('/api/auth/logout',{method:'POST'});
 appState.user=null;appState.stream=null;appState.favorites=[];appState.results=[];appState.history=[];appState.users=[];appState.view='dashboard';
 $('usersList').replaceChildren();$('historyList').replaceChildren();$('searchResults').replaceChildren();$('searchResults2').replaceChildren();$('favoritesList').replaceChildren();$('favoritesFullList').replaceChildren();await bootstrap();
 }catch(err){toast(err.message,true);}}
$('logoutButton').addEventListener('click',signOut);
function resetVideo(){const video=$('video');if(appState.hls){appState.hls.destroy();appState.hls=null;}video.pause();video.removeAttribute('src');video.load();$('videoPlaceholder').hidden=false;}
function playerStatus(text,error=false){$('playerMessage').textContent=text;$('playerMessage').classList.toggle('error',error);$('streamState').textContent=error?'Erreur':appState.stream?'Lecture':'Prêt';$('playerPill').textContent=error?'Erreur':appState.stream?'Actif':'En attente';$('streamPill').textContent=appState.stream?'En cours':'En attente';}
function attachHLS(url){
 const video=$('video');$('videoPlaceholder').hidden=true;
 if(window.Hls&&Hls.isSupported()){
   const hls=new Hls({liveSyncDurationCount:3,maxBufferLength:35});appState.hls=hls;
   hls.on(Hls.Events.MANIFEST_PARSED,()=>{playerStatus('Flux prêt. Cliquez sur lecture si nécessaire.');video.play().catch(()=>{});});
   hls.on(Hls.Events.ERROR,(_event,data)=>{
     if(!data.fatal)return;
     if(data.type===Hls.ErrorTypes.NETWORK_ERROR)hls.startLoad();
     else if(data.type===Hls.ErrorTypes.MEDIA_ERROR)hls.recoverMediaError();
     else playerStatus('Erreur du lecteur HLS : '+data.details,true);
   });hls.loadSource(url);hls.attachMedia(video);
 }else if(video.canPlayType('application/vnd.apple.mpegurl')){
   video.src=url;video.play().catch(()=>{});
 }else{playerStatus('Lecture HLS indisponible : vérifier le chargement de hls.js.',true);}
}
async function play(contentId,name='Flux manuel'){
 if(appState.busy)return;setBusy(true);playerStatus('Préparation du flux (jusqu’à 30 secondes)…');
 try{
  if(appState.stream)await api(`/api/streams/${appState.stream.stream_id}`,{method:'DELETE'});
  appState.stream=null;resetVideo();
  const data=await api('/api/streams/start',{method:'POST',body:{content_id:contentId,name}});
  appState.stream={stream_id:data.stream_id,playlist:data.playlist,name};
  $('streamTitle').textContent=name;playerStatus('Flux initialisé, lecture en préparation…');attachHLS(data.playlist);
  monitor();
 }catch(err){appState.stream=null;resetVideo();playerStatus(err.message,true);toast(err.message,true);}finally{setBusy(false);}
}
async function stopStream(){if(!appState.stream)return;try{await api(`/api/streams/${appState.stream.stream_id}`,{method:'DELETE'});}catch(err){toast(err.message,true);}appState.stream=null;resetVideo();$('streamTitle').textContent='Aucune lecture en cours';playerStatus('Lecture arrêtée.');}
$('manualPlay').addEventListener('click',()=>play($('manualInput').value,'Flux manuel'));
$('manualInput').addEventListener('keydown',event=>{if(event.key==='Enter')play($('manualInput').value,'Flux manuel');});
$('stopBtn').addEventListener('click',stopStream);
async function loadStreams(){try{const streams=(await api('/api/streams')).streams;
 if(streams.length){const own=streams.find(s=>s.owner===appState.user.id)||streams[0];appState.stream={stream_id:own.stream_id,playlist:own.playlist,name:own.name};$('streamTitle').textContent=own.name;attachHLS(own.playlist);playerStatus('Lecture existante retrouvée.');}
 }catch(err){console.warn(err);}}
window.addEventListener('keydown',event=>{if(event.key==='Escape'){$('modalLayer').hidden=true;$('userLayer').hidden=true;}});
setInterval(()=>{if(appState.user&&document.visibilityState==='visible')monitor();},15000);
bootstrap();
