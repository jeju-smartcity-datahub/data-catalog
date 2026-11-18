import assert from 'node:assert/strict';

/** Check painted control boundaries; axe's text contrast rule does not cover these. */
export async function assertControlContrast(locator, name, mode = 'border') {
  const measured = await locator.evaluate((element, mode) => {
    const rgba = value => value.match(/[\d.]+/g).map(Number);
    const composite = (foreground, background) => {
      const alpha = foreground[3] ?? 1;
      return foreground.slice(0, 3).map((value, index) => value * alpha + background[index] * (1 - alpha));
    };
    const background = current => current ? composite(rgba(getComputedStyle(current).backgroundColor), background(current.parentElement)) : [255, 255, 255];
    const luminance = color => color.map(value => { const srgb = value / 255; return srgb <= 0.04045 ? srgb / 12.92 : ((srgb + 0.055) / 1.055) ** 2.4; }).reduce((total, value, index) => total + value * [0.2126, 0.7152, 0.0722][index], 0);
    const contrast = (first, second) => { const values = [luminance(first), luminance(second)]; return (Math.max(...values) + 0.05) / (Math.min(...values) + 0.05); };
    const style = getComputedStyle(element), inside = background(element), outside = background(element.parentElement);
    if (mode === 'inset') {
      const shadowColor = style.boxShadow.match(/rgba?\([^)]+\)/)?.[0];
      const spread = style.boxShadow.replace(/rgba?\([^)]+\)/, '').match(/-?[\d.]+px/g)?.[3];
      const indicator = composite(rgba(shadowColor ?? 'rgba(0, 0, 0, 0)'), inside);
      return [{ side: 'selected inset', width: style.boxShadow.includes('inset') ? Number.parseFloat(spread ?? '0') : 0, style: 'solid', inside: contrast(indicator, inside), outside: contrast(indicator, outside) }];
    }
    return ['Top', 'Right', 'Bottom', 'Left'].map(side => {
      const border = composite(rgba(style[`border${side}Color`]), inside);
      return { side, width: Number.parseFloat(style[`border${side}Width`]), style: style[`border${side}Style`], inside: contrast(border, inside), outside: contrast(border, outside) };
    });
  }, mode);
  for (const border of measured) {
    assert.ok(border.width > 0 && border.style !== 'none', `${name}: ${border.side} boundary must be visible`);
    assert.ok(border.inside >= 3 && border.outside >= 3, `${name}: ${border.side} contrast ${border.inside.toFixed(2)} / ${border.outside.toFixed(2)} must be >= 3:1`);
  }
  return { name, minimum: Math.min(...measured.flatMap(border => [border.inside, border.outside])) };
}
