(() => {
  'use strict';

  const EYE = `
    <svg viewBox="0 0 24 24"
         fill="none"
         stroke="currentColor"
         stroke-width="2"
         stroke-linecap="round"
         stroke-linejoin="round">
      <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z"/>
      <circle cx="12" cy="12" r="3"/>
    </svg>
  `;

  const EYE_OFF = `
    <svg viewBox="0 0 24 24"
         fill="none"
         stroke="currentColor"
         stroke-width="2"
         stroke-linecap="round"
         stroke-linejoin="round">
      <path d="M3 3l18 18"/>
      <path d="M10.6 10.6a2 2 0 002.8 2.8"/>
      <path d="M9.9 4.2A10.7 10.7 0 0112 4c6.5 0 10 8 10 8a17.7 17.7 0 01-2.1 3.2"/>
      <path d="M6.2 6.2C3.5 8.1 2 12 2 12s3.5 8 10 8a9.8 9.8 0 004.1-.9"/>
    </svg>
  `;

  function update(){
    const keyboard =
      document.getElementById('tgWifiKeyboard');

    if(!keyboard) return;

    const button =
      keyboard.querySelector('.tg-kb-eye');

    const input =
      document.querySelector(
        '#tgKbPreviewInput'
      );

    if(!button || !input) return;

    // Only touch the DOM when the icon must change. Writing innerHTML is itself
    // a DOM mutation; doing it on every mutation made the MutationObserver below
    // call update() forever, freezing the kiosk the moment the Wi-Fi keyboard
    // was first built (keyboard never shown, Cancel/Connect untouchable).
    const state = input.type === 'text' ? 'hide' : 'show';
    if (button.dataset.eyeState === state) return;
    button.dataset.eyeState = state;

    button.innerHTML =
      input.type === 'text'
        ? EYE_OFF
        : EYE;

    button.setAttribute(
      'aria-label',
      input.type === 'text'
        ? 'Hide password'
        : 'Show password'
    );

    button.title =
      input.type === 'text'
        ? 'Hide password'
        : 'Show password';
  }

  document.addEventListener(
    'click',
    e => {
      if(
        e.target.closest('.tg-kb-eye')
      ){
        setTimeout(update, 0);
      }
    },
    true
  );

  new MutationObserver(update)
    .observe(
      document.documentElement,
      {
        childList:true,
        subtree:true
      }
    );

  setTimeout(update, 500);
})();
