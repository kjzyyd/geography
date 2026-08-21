package main

import "math"

// HaversineMeters 计算两点间地表距离(米)
func HaversineMeters(lat1, lng1, lat2, lng2 float64) float64 {
	if lat1 == 0 && lng1 == 0 {
		return -1
	}
	if lat2 == 0 && lng2 == 0 {
		return -1
	}
	const R = 6371008.8
	rlat1 := lat1 * math.Pi / 180
	rlat2 := lat2 * math.Pi / 180
	dlat := (lat2 - lat1) * math.Pi / 180
	dlng := (lng2 - lng1) * math.Pi / 180
	a := math.Sin(dlat/2)*math.Sin(dlat/2) +
		math.Cos(rlat1)*math.Cos(rlat2)*math.Sin(dlng/2)*math.Sin(dlng/2)
	return 2 * R * math.Asin(math.Sqrt(a))
}

// FormatDistance 格式化距离显示
func FormatDistance(meters float64) string {
	if meters < 0 {
		return "未设中心"
	}
	if meters >= 1000 {
		return round2(meters/1000) + " km"
	}
	return itoa(int(meters)) + " 米"
}

func round2(v float64) string {
	// 保留 2 位小数的字符串
	i := int(v*100 + 0.5)
	return itoa(i/100) + "." + pad2(i%100)
}

func itoa(v int) string {
	if v == 0 {
		return "0"
	}
	neg := v < 0
	if neg {
		v = -v
	}
	var b [20]byte
	i := len(b)
	for v > 0 {
		i--
		b[i] = byte('0' + v%10)
		v /= 10
	}
	if neg {
		i--
		b[i] = '-'
	}
	return string(b[i:])
}

func pad2(v int) string {
	if v < 10 {
		return "0" + itoa(v)
	}
	return itoa(v)
}
