(() => {
    const confirm = document.querySelector('.batch-confirm button');
    const choices = document.querySelector('[data-order-choices]');
    if (choices) {
        const originalText = confirm?.textContent;
        const initiallyDisabled = confirm?.disabled;
        const previewed = [...document.querySelectorAll('.batch-confirm input[name="selected"]')].map(input => input.value).join(',');
        const sync = () => {
            const selected = [...choices.querySelectorAll('input:checked')].map(input => input.value).join(',');
            if (confirm) {
                const changed = selected !== previewed;
                confirm.disabled = initiallyDisabled || changed;
                confirm.textContent = changed ? 'Сначала нажми «Показать на карте»' : originalText;
            }
        };
        choices.addEventListener('change', sync);
        for (const grip of choices.querySelectorAll('.order-grip')) {
            const row = grip.closest('.order-choice');
            grip.addEventListener('pointerdown', event => {
                if (event.button !== 0) return;
                grip.setPointerCapture(event.pointerId);
                row.classList.add('is-moving');
            });
            grip.addEventListener('pointermove', event => {
                if (!grip.hasPointerCapture(event.pointerId)) return;
                const target = document.elementFromPoint(event.clientX, event.clientY)?.closest('.order-choice');
                if (!target || target === row || target.parentElement !== choices) return;
                const rect = target.getBoundingClientRect();
                if (event.clientY < rect.top + rect.height / 2) target.before(row);
                else target.after(row);
                // Capture can be lost when a node moves in the DOM.
                grip.setPointerCapture(event.pointerId);
                sync();
            });
            grip.addEventListener('lostpointercapture', () => row.classList.remove('is-moving'));
            grip.addEventListener('keydown', event => {
                const up = event.key === 'ArrowUp';
                if (!up && event.key !== 'ArrowDown') return;
                const neighbor = up ? row.previousElementSibling : row.nextElementSibling;
                if (!neighbor) return;
                event.preventDefault();
                if (up) neighbor.before(row);
                else neighbor.after(row);
                grip.focus();
                sync();
            });
        }
    }

    const help = document.getElementById('point-help');
    if (typeof L === 'undefined') {
        help.textContent = 'Карта не загрузилась. Точки можно ввести по координатам.';
        return;
    }
    const map = L.map('route-map').setView([55.577, 42.039], 14);
    map.attributionControl.setPrefix(false);
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19,
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap contributors</a>'
    }).addTo(map);
    const bounds = L.latLngBounds([]);
    const line = window.kstoluRouteLine;
    if (line?.length) {
        const path = L.polyline(line.map(([lon, lat]) => [lat, lon]), { color: '#1e54a2', weight: 5 }).addTo(map);
        bounds.extend(path.getBounds());
    }
    let selectedForm;
    let draft;
    for (const form of document.querySelectorAll('.point-form')) {
        const { latitude, longitude } = form.elements;
        if (latitude.value && longitude.value) {
            const point = [latitude.valueAsNumber, longitude.valueAsNumber];
            L.circleMarker(point, { radius: 8, color: '#222', fillOpacity: 1, fillColor: '#fff' })
                .addTo(map).bindTooltip(form.dataset.stop, { permanent: true, direction: 'right' });
            bounds.extend(point);
        }
        const picker = form.querySelector('.pick-point');
        picker.hidden = false;
        picker.addEventListener('click', () => {
            selectedForm = form;
            help.textContent = 'Нажми на карту, чтобы указать точку: ' + form.dataset.stop + '.';
            document.getElementById('route-map').scrollIntoView({ block: 'center' });
        });
    }
    if (bounds.isValid()) map.fitBounds(bounds, { padding: [30, 30], maxZoom: 16 });
    map.on('click', ({ latlng }) => {
        if (!selectedForm) return;
        selectedForm.elements.latitude.value = latlng.lat.toFixed(6);
        selectedForm.elements.longitude.value = latlng.lng.toFixed(6);
        if (draft) draft.remove();
        draft = L.circleMarker(latlng, { radius: 8, color: '#1e54a2' }).addTo(map);
        help.textContent = 'Точка выбрана. Сохрани ее в форме.';
        selectedForm.querySelector('button[type="submit"], button:last-child').focus();
        selectedForm = null;
    });
})();
