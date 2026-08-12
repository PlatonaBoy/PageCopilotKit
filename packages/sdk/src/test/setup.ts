// jsdom has no layout engine, so getBoundingClientRect always reports zeros and every element
// would look invisible to PageEngine. Give elements a plausible box unless a test overrides it.
Element.prototype.getBoundingClientRect = function getBoundingClientRect(this: Element): DOMRect {
  const hidden = (this as HTMLElement).dataset?.testHidden === 'true';
  const size = hidden ? 0 : 100;
  return {
    width: size,
    height: hidden ? 0 : 20,
    top: 0,
    bottom: hidden ? 0 : 20,
    left: 0,
    right: size,
    x: 0,
    y: 0,
    toJSON() {
      return {};
    },
  } as DOMRect;
};

if (!('clipboard' in navigator)) {
  Object.defineProperty(navigator, 'clipboard', {
    value: { writeText: () => Promise.resolve() },
    configurable: true,
  });
}
