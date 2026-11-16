(() => {
    const select = document.querySelector('[name="addressType"]');
    const field = document.getElementById('apartment-field');
    const apartment = field.querySelector('input');
    const entrance = document.getElementById('entrance-field');
    const update = () => {
        const needsApartment = select.value === 'APARTMENT';
        field.hidden = !needsApartment;
        apartment.required = needsApartment;
        apartment.disabled = !needsApartment;
        entrance.hidden = !needsApartment;
        entrance.querySelector('input').disabled = !needsApartment;
    };
    select.addEventListener('change', update);
    update();
})();