package uiserver

import "testing"

func TestNotifyUserDeliversToEveryStream(t *testing.T) {
	s := New(Deps{})
	ch1, cancel1 := s.notices()
	defer cancel1()
	ch2, cancel2 := s.notices()
	defer cancel2()

	s.NotifyUser(Notice{Kind: "send", Peer: "Alice", Files: 2, Total: 1234})

	for i, ch := range []<-chan Notice{ch1, ch2} {
		select {
		case n := <-ch:
			if n.Kind != "send" || n.Peer != "Alice" || n.Files != 2 || n.Total != 1234 {
				t.Fatalf("stream %d got %+v", i, n)
			}
		default:
			t.Fatalf("stream %d received nothing", i)
		}
	}
}

func TestNotifyUserWithNoStreamsDoesNotBlock(t *testing.T) {
	s := New(Deps{})
	s.NotifyUser(Notice{Kind: "download"}) // must not panic or block
}

func TestCancelledStreamStopsReceiving(t *testing.T) {
	s := New(Deps{})
	ch, cancel := s.notices()
	cancel()
	s.NotifyUser(Notice{Kind: "receive"})
	select {
	case n := <-ch:
		t.Fatalf("cancelled stream still received %+v", n)
	default:
	}
}
