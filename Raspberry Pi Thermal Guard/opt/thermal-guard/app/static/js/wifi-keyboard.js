(() => {
  'use strict';

  let keyboard = null;
  let activeInput = null;
  let shift = false;
  let caps = false;

  const letters = [
    ['q','w','e','r','t','y','u','i','o','p'],
    ['a','s','d','f','g','h','j','k','l'],
    ['z','x','c','v','b','n','m']
  ];

  const numbers = ['1','2','3','4','5','6','7','8','9','0'];
  const symbols = ['@','#','$','%','&','*','!','?','_','-','.'];

  function isWifiPassword(input) {
    if (!input || input.tagName !== 'INPUT') return false;
    if (input.type !== 'password' && input.type !== 'text') return false;

    const own = [
      input.id || '',
      input.name || '',
      input.placeholder || '',
      input.getAttribute('aria-label') || ''
    ].join(' ').toLowerCase();

    let parent = input;
    let context = '';

    for (let i = 0; i < 6 && parent; i++, parent = parent.parentElement) {
      context += ' ' + [
        parent.id || '',
        parent.className || '',
        parent.getAttribute?.('data-page') || '',
        parent.getAttribute?.('data-section') || '',
        parent.getAttribute?.('aria-label') || ''
      ].join(' ');
    }

    context = context.toLowerCase();

    const passwordLike =
      own.includes('password') ||
      own.includes('passphrase') ||
      own.includes('wifi-pass') ||
      own.includes('network-pass');

    const wifiLike =
      own.includes('wifi') ||
      own.includes('wi-fi') ||
      context.includes('wifi') ||
      context.includes('wi-fi') ||
      context.includes('wireless') ||
      context.includes('network');

    return passwordLike && wifiLike;
  }

  function injectStyle() {
    if (document.getElementById('tgWifiKeyboardStyle')) return;

    const style = document.createElement('style');
    style.id = 'tgWifiKeyboardStyle';

    style.textContent = `
      #tgWifiKeyboard {
        position: fixed;
        left: 50%;
        bottom: 14px;
        transform: translateX(-50%) translateY(120%);
        width: min(900px, 96vw);
        z-index: 1000005;
        opacity: 0;
        visibility: hidden;
        pointer-events: none;

        background:
          linear-gradient(180deg, rgba(23,42,56,.98), rgba(8,20,29,.99));

        border: 1px solid rgba(138,190,220,.20);
        border-radius: 22px;
        padding: 14px;

        box-shadow:
          0 24px 70px rgba(0,0,0,.55),
          inset 0 1px 0 rgba(255,255,255,.04);

        backdrop-filter: blur(18px);
        transition:
          transform .22s ease,
          opacity .22s ease,
          visibility .22s ease;
      }

      #tgWifiKeyboard.open {
        transform: translateX(-50%) translateY(0);
        opacity: 1;
        visibility: visible;
        pointer-events: auto;
      }

      .tg-kb-top {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 12px;
        margin-bottom: 10px;
      }

      .tg-kb-title-wrap {
        min-width: 0;
      }

      .tg-kb-title {
        color: #f4f9fc;
        font-size: 17px;
        font-weight: 800;
      }

      .tg-kb-subtitle {
        color: #8faebe;
        font-size: 12px;
        margin-top: 2px;
      }

      .tg-kb-close {
        width: 42px;
        height: 42px;
        border: 0;
        border-radius: 12px;
        background: rgba(255,255,255,.08);
        color: #fff;
        font-size: 22px;
        cursor: pointer;
      }

      .tg-kb-preview {
        display: flex;
        align-items: center;
        gap: 8px;
        margin-bottom: 12px;
      }

      .tg-kb-preview input {
        flex: 1;
        min-width: 0;
        height: 48px;
        border-radius: 12px;
        border: 1px solid rgba(144,194,220,.25);
        background: #f8fbfd;
        color: #10212c;
        padding: 0 14px;
        font-size: 18px;
        outline: none;
      }

      .tg-kb-eye {
        height: 48px;
        min-width: 52px;
        border: 0;
        border-radius: 12px;
        background: #193a4f;
        color: #fff;
        font-size: 20px;
        cursor: pointer;
      }

      .tg-kb-row {
        display: flex;
        gap: 6px;
        justify-content: center;
        margin: 6px 0;
      }

      .tg-kb-key {
        flex: 1 1 0;
        min-width: 0;
        height: 50px;

        border: 1px solid rgba(255,255,255,.07);
        border-radius: 11px;

        background:
          linear-gradient(180deg, #1d4258, #153246);

        color: #f7fbfd;
        font-size: 18px;
        font-weight: 750;

        cursor: pointer;
        touch-action: manipulation;
        user-select: none;

        box-shadow:
          inset 0 1px 0 rgba(255,255,255,.05),
          0 3px 8px rgba(0,0,0,.12);
      }

      .tg-kb-key:active {
        transform: translateY(1px) scale(.97);
        background: #285b77;
      }

      .tg-kb-special {
        background:
          linear-gradient(180deg, #263d4b, #1b2f3b);
      }

      .tg-kb-shift.active,
      .tg-kb-caps.active {
        background:
          linear-gradient(180deg, #237ed1, #1766ad);
      }

      .tg-kb-backspace {
        background:
          linear-gradient(180deg, #8e4141, #6e3030);
      }

      .tg-kb-done {
        background:
          linear-gradient(180deg, #27a467, #197d4c);
      }

      .tg-kb-space {
        flex: 4 1 0;
      }

      .tg-kb-wide {
        flex: 1.6 1 0;
      }

      @media (max-height: 720px) {
        #tgWifiKeyboard {
          bottom: 6px;
          padding: 9px;
          border-radius: 16px;
        }

        .tg-kb-key {
          height: 40px;
          font-size: 15px;
        }

        .tg-kb-preview input,
        .tg-kb-eye {
          height: 40px;
        }

        .tg-kb-row {
          margin: 4px 0;
        }

        .tg-kb-top {
          margin-bottom: 6px;
        }
      }
    `;

    document.head.appendChild(style);
  }

  function fireInput() {
    if (!activeInput) return;

    activeInput.dispatchEvent(
      new Event('input', { bubbles: true })
    );

    activeInput.dispatchEvent(
      new Event('change', { bubbles: true })
    );
  }

  function put(text) {
    if (!activeInput) return;

    const start =
      typeof activeInput.selectionStart === 'number'
        ? activeInput.selectionStart
        : activeInput.value.length;

    const end =
      typeof activeInput.selectionEnd === 'number'
        ? activeInput.selectionEnd
        : activeInput.value.length;

    activeInput.setRangeText(text, start, end, 'end');

    fireInput();
    syncPreview();
    activeInput.focus();
  }

  function erase() {
    if (!activeInput) return;

    const start =
      activeInput.selectionStart ?? activeInput.value.length;

    const end =
      activeInput.selectionEnd ?? activeInput.value.length;

    if (start !== end) {
      activeInput.setRangeText('', start, end, 'end');
    } else if (start > 0) {
      activeInput.setRangeText('', start - 1, start, 'end');
    }

    fireInput();
    syncPreview();
    activeInput.focus();
  }

  function clearInput() {
    if (!activeInput) return;

    activeInput.value = '';
    fireInput();
    syncPreview();
    activeInput.focus();
  }

  function syncPreview() {
    if (!keyboard || !activeInput) return;

    const preview =
      keyboard.querySelector('#tgKbPreviewInput');

    if (!preview) return;

    preview.value = activeInput.value;
    preview.type = activeInput.type;
  }

  function key(label, action, className = '') {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = `tg-kb-key ${className}`;
    btn.textContent = label;

    btn.addEventListener('pointerdown', event => {
      event.preventDefault();
      event.stopPropagation();
      action();
    });

    return btn;
  }

  function characterButton(ch) {
    const btn = key(ch, () => {
      let value = ch;

      if (/^[a-z]$/i.test(ch)) {
        value =
          (shift || caps)
            ? ch.toUpperCase()
            : ch.toLowerCase();
      }

      put(value);

      if (shift) {
        shift = false;
        refreshKeys();
      }
    });

    btn.dataset.char = ch;

    return btn;
  }

  function refreshKeys() {
    if (!keyboard) return;

    keyboard.querySelectorAll('[data-char]').forEach(btn => {
      const ch = btn.dataset.char;

      if (/^[a-z]$/i.test(ch)) {
        btn.textContent =
          (shift || caps)
            ? ch.toUpperCase()
            : ch.toLowerCase();
      }
    });

    keyboard
      .querySelector('.tg-kb-shift')
      ?.classList.toggle('active', shift);

    keyboard
      .querySelector('.tg-kb-caps')
      ?.classList.toggle('active', caps);
  }

  function build() {
    if (keyboard) return keyboard;

    injectStyle();

    keyboard = document.createElement('div');
    keyboard.id = 'tgWifiKeyboard';

    const top = document.createElement('div');
    top.className = 'tg-kb-top';

    const titleWrap = document.createElement('div');
    titleWrap.className = 'tg-kb-title-wrap';

    const title = document.createElement('div');
    title.className = 'tg-kb-title';
    title.textContent = 'Wi-Fi Password';

    const subtitle = document.createElement('div');
    subtitle.className = 'tg-kb-subtitle';
    subtitle.textContent = 'Enter your network password';

    titleWrap.append(title, subtitle);

    const close = document.createElement('button');
    close.type = 'button';
    close.className = 'tg-kb-close';
    close.textContent = '×';
    close.onclick = hide;

    top.append(titleWrap, close);

    const previewWrap = document.createElement('div');
    previewWrap.className = 'tg-kb-preview';

    const preview = document.createElement('input');
    preview.id = 'tgKbPreviewInput';
    preview.type = 'password';
    preview.readOnly = true;

    const eye = document.createElement('button');
    eye.type = 'button';
    eye.className = 'tg-kb-eye';
    eye.textContent = '👁';

    eye.onclick = () => {
      if (!activeInput) return;

      const visible = activeInput.type === 'text';

      activeInput.type = visible ? 'password' : 'text';
      preview.type = activeInput.type;

      activeInput.focus();
    };

    previewWrap.append(preview, eye);

    keyboard.append(top, previewWrap);

    const numberRow = document.createElement('div');
    numberRow.className = 'tg-kb-row';

    numbers.forEach(ch =>
      numberRow.appendChild(characterButton(ch))
    );

    keyboard.appendChild(numberRow);

    letters.forEach(row => {
      const line = document.createElement('div');
      line.className = 'tg-kb-row';

      row.forEach(ch =>
        line.appendChild(characterButton(ch))
      );

      keyboard.appendChild(line);
    });

    const symbolRow = document.createElement('div');
    symbolRow.className = 'tg-kb-row';

    symbols.forEach(ch =>
      symbolRow.appendChild(characterButton(ch))
    );

    keyboard.appendChild(symbolRow);

    const bottom = document.createElement('div');
    bottom.className = 'tg-kb-row';

    bottom.append(
      key('Shift', () => {
        shift = !shift;
        refreshKeys();
      }, 'tg-kb-special tg-kb-wide tg-kb-shift'),

      key('Caps', () => {
        caps = !caps;
        refreshKeys();
      }, 'tg-kb-special tg-kb-wide tg-kb-caps'),

      key('Space', () => {
        put(' ');
      }, 'tg-kb-special tg-kb-space'),

      key('⌫', erase, 'tg-kb-backspace tg-kb-wide'),

      key('Clear', clearInput, 'tg-kb-special tg-kb-wide'),

      key('Done', hide, 'tg-kb-done tg-kb-wide')
    );

    keyboard.appendChild(bottom);

    document.body.appendChild(keyboard);

    refreshKeys();

    return keyboard;
  }

  function show(input) {
    activeInput = input;

    const kb = build();

    syncPreview();

    kb.classList.add('open');

    setTimeout(() => {
      activeInput?.focus();
    }, 50);
  }

  function hide() {
    keyboard?.classList.remove('open');

    if (activeInput) {
      activeInput.focus();
    }
  }

  document.addEventListener('focusin', event => {
    const input = event.target;

    if (isWifiPassword(input)) {
      show(input);
    }
  });

  document.addEventListener(
    'pointerdown',
    event => {
      const input = event.target;

      if (isWifiPassword(input)) {
        show(input);
      }
    },
    true
  );

  document.addEventListener('input', event => {
    if (event.target === activeInput) {
      syncPreview();
    }
  });

  window.TGWifiKeyboard = {
    show,
    hide
  };
})();
