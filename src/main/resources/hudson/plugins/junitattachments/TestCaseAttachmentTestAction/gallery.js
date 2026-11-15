document.addEventListener("DOMContentLoaded", () => {
  document.querySelectorAll("a.gallery").forEach((el) => {
    el.addEventListener("click", (evt) => {
      // Let modified clicks open the image in a new tab or window, as for any other link
      if (
        evt.button !== 0 ||
        evt.ctrlKey ||
        evt.metaKey ||
        evt.shiftKey ||
        evt.altKey
      ) {
        return;
      }
      evt.preventDefault();

      const name = el.textContent.trim();
      const img = new Image();
      img.alt = name;
      img.style.display = "block";
      img.style.margin = "0 auto";
      img.style.height = "auto";
      img.style.maxWidth = "100%";

      const open = () => {
        if (img.naturalWidth > 0 && img.naturalHeight > 0) {
          // Scale large images down to fit the dialog, keeping their aspect ratio.
          // Small images keep their natural size.
          const ratio = img.naturalWidth / img.naturalHeight;
          img.style.width = `min(${img.naturalWidth}px, calc(90vw - 2.5rem), calc(70vh * ${ratio}))`;
        }
        dialog.modal(img, {
          title: name,
          // The default maximum width of 475px suits forms and messages, not screenshots
          maxWidth: "90vw",
        });
      };
      // Wait for the image, so the dialog is sized for it rather than resizing once it arrives
      img.addEventListener("load", open, { once: true });
      img.addEventListener("error", open, { once: true });
      img.src = el.href;
    });
  });
});
