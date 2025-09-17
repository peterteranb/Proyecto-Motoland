// productos-ubicacion.js
// Utilidades para manejar y formar etiquetas de ubicación de productos.
// Coloca este archivo en la misma carpeta que tus HTML (subCarpetasMenu).
(function () {
    'use strict';

    const API = {
        /**
         * Dado un objeto producto, devuelve una etiqueta legible para la UI.
         * Devuelve cadenas como:
         *   " - Ubicacion: P2/R32/C"
         * o '' si no hay información de ubicación.
         */
        etiquetaUbicacion: function (product) {
            if (!product || typeof product !== 'object') return '';

            // 1) Si ya viene una etiqueta completa desde el backend
            if (product.ubicacion && String(product.ubicacion).trim() !== '') {
                return ' - Ubicacion: ' + String(product.ubicacion).trim();
            }

            // 2) Si vienen columnas estructuradas zona/rack/altura
            const zona = product.ubicacion_zona || product.zona || product.zone || null;
            const rack = product.ubicacion_rack || product.rack || null;
            const altura = product.ubicacion_altura || product.altura || product.level || null;

            if (zona || rack || altura) {
                // si falta algún componente, devolvemos '' (o podríamos devolver 'Incompleta')
                if (!zona || !rack || !altura) return ' - Ubicacion: Incompleta';
                return ' - Ubicacion: P' + zona + '/R' + rack + '/' + String(altura).toUpperCase();
            }

            // 3) Si no hay datos, devolvemos vacío (el frontend puede usar fallback)
            return '';
        },

        /**
         * Valida si una ubicación dada (zona, rack, altura) es completa y válida.
         * Retorna true si es válida, false si no.
         */
        validarUbicacionParts: function (zona, rack, altura) {
            if (!zona && !rack && !altura) return true; // vacío permitido (significa "sin ubicación")
            if (!zona || !rack || !altura) return false;
            const z = parseInt(zona, 10);
            const r = parseInt(rack, 10);
            const a = String(altura).toUpperCase();
            if (!Number.isInteger(z) || z < 1 || z > 4) return false;
            if (!Number.isInteger(r) || r < 1 || r > 50) return false;
            if (!['A','B','C','D','E'].includes(a)) return false;
            return true;
        },

        /**
         * Devuelve HTML para un control compacto de ubicación (3 selects) si lo necesitas
         * (aunque en nuestra solución principal generamos selects en los HTML).
         */
        crearInputUbicacionHtml: function () {
            const container = document.createElement('div');
            container.className = 'ubicacion-grid';

            const crearSelect = (name, placeholder, items) => {
                const s = document.createElement('select');
                s.name = name;
                const ph = document.createElement('option');
                ph.value = '';
                ph.textContent = placeholder;
                s.appendChild(ph);
                items.forEach(it => {
                    const o = document.createElement('option');
                    o.value = it.value;
                    o.textContent = it.label;
                    s.appendChild(o);
                });
                return s;
            };

            const zona = crearSelect('ubicacion_zona', 'Zona', [{value:'1',label:'P1'},{value:'2',label:'P2'},{value:'3',label:'P3'},{value:'4',label:'P4'}]);
            const racks = Array.from({length:50}, (_,i)=>({value:String(i+1), label:'R'+(i+1)}));
            const rack = crearSelect('ubicacion_rack', 'Rack', racks);
            const altura = crearSelect('ubicacion_altura', 'Altura', [{value:'A',label:'A'},{value:'B',label:'B'},{value:'C',label:'C'},{value:'D',label:'D'},{value:'E',label:'E'}]);

            container.appendChild(zona);
            container.appendChild(rack);
            container.appendChild(altura);

            return container;
        }
    };

    // Exponer en window (API segura)
    try {
        window.ProductosUbicacion = API;
        // También exponer como identificador libre (compatibilidad):
        // Esto creará la variable global ProductosUbicacion en entornos que permitan declarar globals.
        if (typeof window !== 'undefined') {
            try {
                // eslint-disable-next-line no-undef, no-restricted-globals
                ProductosUbicacion = API; // puede lanzar en modo strict si no se permite, por eso el try
            } catch (e) {
                // si no se puede crear el identificador libre lo ignoramos; siempre se puede acceder vía window.ProductosUbicacion
            }
        }
    } catch (err) {
        // en caso raro de fallo, guardarlo en console para debug
        console.error('No se pudo exponer ProductosUbicacion globalmente:', err);
    }
})();
