(() => {
  'use strict';

  const interactiveSelector = [
    '#tgWifiPanel button',
    '#tgWifiConnectBox button',
    '#tgWifiPassword',
    '.tgWifiNetwork',
    '.tgWifiRefresh',
    '.tgWifiClose',
    '.tgWifiConnect',

    '.wifi-settings button',
    '.wifi-settings input',
    '.wifi-panel button',
    '.wifi-panel input',

    '.wifi-dialog button',
    '.wifi-dialog input',

    '.wifi-modal button',
    '.wifi-modal input',

    '.network-dialog button',
    '.network-dialog input',

    '.network-modal button',
    '.network-modal input',

    '.modal.open button',
    '.modal.open input',

    '.drawer.open button',
    '.drawer.open input',

    '#tgWifiKeyboard button'
  ].join(',');

  function isBlockingLayer(el){
    if (!el || !el.classList) return false;

    return (
      el.classList.contains('drawer-overlay') ||
      el.classList.contains('modal-backdrop')
    );
  }

  // Write a style only when it differs: an unchanged write still costs a
  // mutation record, and this script reacts to style mutations itself.
  function setStyle(el, prop, value){
    if (el.style[prop] !== value) el.style[prop] = value;
  }

  function repairNow(){
    document
      .querySelectorAll(interactiveSelector)
      .forEach(el => {
        if (el.disabled) return;

        setStyle(el, 'pointerEvents', 'auto');
        setStyle(el, 'touchAction', 'manipulation');
      });

    document
      .querySelectorAll('.modal.open')
      .forEach(el => {
        setStyle(el, 'pointerEvents', 'auto');
        setStyle(el, 'zIndex', '10010');
      });

    document
      .querySelectorAll('.drawer.open')
      .forEach(el => {
        setStyle(el, 'pointerEvents', 'auto');
        setStyle(el, 'zIndex', '9010');
      });

    const keyboard =
      document.getElementById('tgWifiKeyboard');

    if (keyboard) {
      const open = keyboard.classList.contains('open');
      setStyle(keyboard, 'pointerEvents', open ? 'auto' : 'none');
      setStyle(keyboard, 'visibility', open ? 'visible' : 'hidden');
    }
  }

  // At most one repair per animation frame, however many mutations arrive.
  // (The dashboard re-renders every few seconds; on a Pi 3 a full-document
  // querySelectorAll per mutation was needless load and a loop risk.)
  let repairQueued = false;
  function repair(){
    if (repairQueued) return;
    repairQueued = true;
    requestAnimationFrame(() => {
      repairQueued = false;
      repairNow();
    });
  }

  /*
   * If backdrop accidentally receives a tap even though
   * a visible Wi-Fi button is underneath, forward the tap
   * to the actual button/input.
   */
  function rescuePointer(event){
    if (!isBlockingLayer(event.target)) return;

    const stack =
      document.elementsFromPoint(
        event.clientX,
        event.clientY
      );

    const intended = stack.find(el => {
      if (el === event.target) return false;

      try {
        return el.matches(interactiveSelector);
      } catch (_) {
        return false;
      }
    });

    if (!intended) return;

    event.preventDefault();
    event.stopImmediatePropagation();

    if (
      intended.tagName === 'INPUT' ||
      intended.tagName === 'TEXTAREA'
    ) {
      intended.focus();
      return;
    }

    if (
      intended.tagName === 'BUTTON' ||
      intended.tagName === 'A' ||
      intended.getAttribute('role') === 'button'
    ) {
      intended.click();
    }
  }

  window.addEventListener(
    'pointerup',
    rescuePointer,
    true
  );

  document.addEventListener(
    'focusin',
    repair
  );

  document.addEventListener(
    'click',
    () => setTimeout(repair, 0),
    false
  );

  new MutationObserver(() => {
    repair();
  }).observe(
    document.documentElement,
    {
      childList:true,
      subtree:true,
      attributes:true,
      attributeFilter:[
        'class',
        'style',
        'hidden',
        'disabled'
      ]
    }
  );

  if (document.readyState === 'loading') {
    document.addEventListener(
      'DOMContentLoaded',
      repair
    );
  } else {
    repair();
  }

  window.ThermalGuardWifiTouchRepair = repairNow;

  console.log(
    '[Thermal Guard] Wi-Fi touch repair active'
  );
})();
