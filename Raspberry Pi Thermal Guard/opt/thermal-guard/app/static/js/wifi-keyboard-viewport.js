(() => {
  'use strict';

  function isWifiPassword(el){
    if (!el || el.tagName !== 'INPUT') return false;

    const own = [
      el.id || '',
      el.name || '',
      el.placeholder || '',
      el.getAttribute('aria-label') || ''
    ].join(' ').toLowerCase();

    let parent = el;
    let context = '';

    for(let i = 0; i < 6 && parent; i++, parent = parent.parentElement){
      context += ' ' + [
        parent.id || '',
        parent.className || '',
        parent.textContent || ''
      ].join(' ');
    }

    context = context.toLowerCase();

    return (
      (
        own.includes('password') ||
        own.includes('passphrase') ||
        own.includes('pass')
      ) &&
      (
        own.includes('wifi') ||
        own.includes('wi-fi') ||
        context.includes('wifi') ||
        context.includes('wi-fi') ||
        context.includes('wireless') ||
        context.includes('network')
      )
    );
  }

  function makeVisible(input){
    document.documentElement.style.scrollPaddingBottom = '235px';

    setTimeout(() => {
      const box =
        input.closest(
          '#tgWifiConnectBox,.wifi-connect,.wifi-form,.network-form,.settings-content,.panel,.card'
        ) || input;

      try{
        box.scrollIntoView({
          behavior:'smooth',
          block:'center',
          inline:'nearest'
        });
      }catch(_){
        input.scrollIntoView();
      }
    }, 120);
  }

  document.addEventListener('focusin', e => {
    if(isWifiPassword(e.target)){
      makeVisible(e.target);
    }
  });

  document.addEventListener('pointerdown', e => {
    if(isWifiPassword(e.target)){
      makeVisible(e.target);
    }
  }, true);

})();
