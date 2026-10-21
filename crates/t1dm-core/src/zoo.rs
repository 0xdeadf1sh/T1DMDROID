//! SPEC/zoo.md §3-§4: the bg window a zoo model reads, and the fan its median and offsets make.

use crate::CoreError;

/// `history` ends at the latest measured reading, NaN where unmeasured; `None` if that end is NaN.
#[uniffi::export]
pub fn zoo_bg_window(history: Vec<f64>, ctx: u32, interp_max_steps: u32) -> Option<Vec<f32>> {
    let ctx = ctx as usize;
    if ctx == 0 || !history.last().is_some_and(|v| v.is_finite()) {
        return None;
    }
    let mut h: Vec<f32> = history.iter().map(|&v| v as f32).collect();
    let n = h.len();
    let mut i = 0;
    while i < n {
        if !h[i].is_nan() {
            i += 1;
            continue;
        }
        let s = i;
        while i < n && h[i].is_nan() {
            i += 1;
        }
        if i - s <= interp_max_steps as usize && s > 0 && i < n {
            let (a, b) = (h[s - 1] as f64, h[i] as f64);
            let span = (i - (s - 1)) as f64;
            for (k, slot) in h.iter_mut().enumerate().take(i).skip(s) {
                *slot = (a + (b - a) * ((k - (s - 1)) as f64 / span)) as f32;
            }
        }
    }
    let mut w = vec![f32::NAN; ctx.saturating_sub(n)];
    w.extend_from_slice(&h[n.saturating_sub(ctx)..]);
    let first = w.iter().copied().find(|v| !v.is_nan())?;
    let mut last = first;
    for v in w.iter_mut() {
        if v.is_nan() {
            *v = last;
        } else {
            last = *v;
        }
    }
    Some(w)
}

/// Step-major `[Z × levels]` fan: `median[k] + offsets[i][k]`, clamped, made non-decreasing.
#[uniffi::export]
pub fn zoo_fan(
    median: Vec<f64>,
    offsets: Vec<Vec<f64>>,
    clamp_min: f64,
    clamp_max: f64,
) -> Result<Vec<f64>, CoreError> {
    let z = median.len();
    if offsets.iter().any(|row| row.len() != z) || !(clamp_min < clamp_max) {
        return Err(CoreError::Decode { reason: format!("zoo fan: {} offsets rows vs {z} steps", offsets.len()) });
    }
    if median.iter().chain(offsets.iter().flatten()).any(|v| !v.is_finite()) {
        return Err(CoreError::Decode { reason: "zoo fan: non-finite value".into() });
    }
    let mut out = Vec::with_capacity(z * offsets.len());
    for k in 0..z {
        let mut prev = f64::NEG_INFINITY;
        for row in &offsets {
            let v = (median[k] + row[k]).clamp(clamp_min, clamp_max).max(prev);
            out.push(v);
            prev = v;
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    const GOLDEN: &str = include_str!("testdata/zoo_window_golden.json");

    #[test]
    fn window_matches_t1dmai_golden() {
        let g: serde_json::Value = serde_json::from_str(GOLDEN).unwrap();
        let interp = g["interp_max_steps"].as_u64().unwrap() as u32;
        let mut worst = 0f32;
        for c in g["cases"].as_array().unwrap() {
            let hist: Vec<f64> = c["history"].as_array().unwrap().iter()
                .map(|v| v.as_f64().unwrap_or(f64::NAN)).collect();
            let want: Vec<f32> = c["window"].as_array().unwrap().iter()
                .map(|v| v.as_f64().unwrap() as f32).collect();
            let got = zoo_bg_window(hist, c["ctx"].as_u64().unwrap() as u32, interp).unwrap();
            assert_eq!(got.len(), want.len());
            for (a, b) in got.iter().zip(&want) {
                worst = worst.max((a - b).abs());
            }
        }
        println!("DUMP zoo window max diff {worst:e} mg/dL");
        assert!(worst < 1e-3);
    }

    #[test]
    fn window_refuses_an_unmeasured_end() {
        assert!(zoo_bg_window(vec![120.0, f64::NAN], 4, 6).is_none());
    }

    #[test]
    fn fan_is_median_plus_offsets_clamped_and_ordered() {
        let fan = zoo_fan(vec![100.0, 395.0], vec![vec![-10.0, -10.0], vec![0.0, 0.0], vec![8.0, 20.0]], 40.0, 400.0)
            .unwrap();
        assert_eq!(fan, vec![90.0, 100.0, 108.0, 385.0, 395.0, 400.0]);
        assert!(zoo_fan(vec![1.0], vec![vec![0.0, 0.0]], 40.0, 400.0).is_err());
    }
}
