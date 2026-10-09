package main

import (
	"reflect"
	"testing"
)

func TestHoistDataDir(t *testing.T) {
	cases := []struct{ in, want []string }{
		{[]string{"--data-dir", "x", "peers"}, []string{"peers", "--data-dir", "x"}},
		{[]string{"--data-dir=x", "get", "d", "s"}, []string{"get", "d", "s", "--data-dir=x"}},
		{[]string{"peers", "--data-dir", "x"}, []string{"peers", "--data-dir", "x"}},
		{[]string{"--data-dir", "x", "--no-browser"}, []string{"--data-dir", "x", "--no-browser"}},
		{[]string{"--data-dir", "x"}, []string{"--data-dir", "x"}},
		{nil, nil},
	}
	for _, c := range cases {
		if got := hoistDataDir(c.in); !reflect.DeepEqual(got, c.want) {
			t.Errorf("hoistDataDir(%v) = %v, want %v", c.in, got, c.want)
		}
	}
}
