"""논문(류권규 2024) 3.2절 재현: 인공 STI 0.1~19 px/fr 에서 C/F/H-STIV 정확도 + 잡음/정지무늬 내성."""
import sys, os, time
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from pipeflow.stiv import artificial_sti, h_stiv, preprocess_sti, c_stiv, f_stiv

U = [0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.5, 2.0, 5.0, 6.0, 7.0, 8.0, 10.0, 13.0, 16.0, 19.0]

def run(title, **kw):
    print(f"\n[{title}]")
    print(f"{'true':>6} {'C':>8} {'F':>8} {'H':>8} {'use':>3} {'errC%':>7} {'errF%':>7} {'errH%':>7} {'agree':>6}")
    eh = []
    for u in U:
        S = artificial_sti(u, **kw)
        r = h_stiv(S, remove_static=True)
        e = lambda x: abs(x - u) / u * 100
        eh.append(e(r['u']))
        print(f"{u:6.1f} {r['u_C']:8.3f} {r['u_F']:8.3f} {r['u']:8.3f} {r['used']:>3} "
              f"{e(r['u_C']):7.2f} {e(r['u_F']):7.2f} {e(r['u']):7.2f} {r['agreement']:6.3f}")
    print(f"H-STIV 평균 오차 {sum(eh)/len(eh):.2f}%, 최대 {max(eh):.2f}%")
    return eh

t = time.time()
run("깨끗한 인공 STI (논문 조건)")
run("잡음 σ=0.3 + 정지 무늬(진폭 1.0)", noise=0.3, static=1.0, seed=1)
# 정지 무늬 제거 효과 비교
print("\n[정지 무늬 제거 on/off, u=0.5 / 3.0 px/fr, 정지무늬 진폭 2.0]")
for u in [0.5, 3.0]:
    S = artificial_sti(u, static=2.0, noise=0.2, seed=2)
    for rs in (False, True):
        r = h_stiv(S, remove_static=rs)
        print(f"u={u} remove_static={rs}:  C={r['u_C']:.3f}  F={r['u_F']:.3f}  H={r['u']:.3f}")
print(f"\n소요 {time.time()-t:.1f}s")
