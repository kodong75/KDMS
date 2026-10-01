// KDMS 화면 스크립트. 외부 라이브러리 없음.
'use strict';

document.addEventListener('DOMContentLoaded', () => {
    const refresh = document.getElementById('refresh');
    if (refresh) {
        refresh.addEventListener('click', () => {
            refresh.disabled = true;
            refresh.textContent = '확인 중…';
            window.location.reload();
        });
    }
});
